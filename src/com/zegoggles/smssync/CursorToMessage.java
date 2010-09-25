/*
 * Copyright (c) 2009 Christoph Studer <chstuder@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.zegoggles.smssync;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.security.MessageDigest;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Contacts.ContactMethods;
import android.provider.Contacts.People;
import android.provider.Contacts.Phones;
import android.util.Log;

import com.fsck.k9.mail.Address;
import com.fsck.k9.mail.Flag;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.Message.RecipientType;
import com.fsck.k9.mail.internet.MimeMessage;
import com.fsck.k9.mail.internet.TextBody;

import org.apache.james.mime4j.codec.EncoderUtil;
import static com.zegoggles.smssync.App.*;

public class CursorToMessage {

    private static final String REFERENCE_UID_TEMPLATE = "<%s.%s@sms-backup-plus.local>";
    private static final String MSG_ID_TEMPLATE = "<%s@sms-backup-plus.local>";

    private static final String[] PHONE_PROJECTION = new String[] {
        Phones.PERSON_ID, People.NAME, Phones.NUMBER
    };

    private static final String UNKNOWN_NUMBER = "unknown_number";
    private static final String UNKNOWN_EMAIL = "unknown.email";
    private static final String UNKNOWN_PERSON = "unknown.person";

    private static final int MAX_PEOPLE_CACHE_SIZE = 500;

    private Context mContext;
    private Address mUserAddress;

    private Map<String, PersonRecord> mPeopleCache;

    private String mReferenceValue;
    private boolean mMarkAsRead = false;

    public static interface Headers {
        String ID = "X-smssync-id";
        String ADDRESS = "X-smssync-address";
        String TYPE  = "X-smssync-type";
        String DATE =  "X-smssync-date";
        String THREAD_ID = "X-smssync-thread";
        String READ = "X-smssync-read";
        String STATUS = "X-smssync-status";
        String PROTOCOL = "X-smssync-protocol";
        String SERVICE_CENTER = "X-smssync-service_center";
        String BACKUP_TIME = "X-smssync-backup-time";
        String VERSION = "X-smssync-version";
    }

    public CursorToMessage(Context ctx, String userEmail) {
        mContext = ctx;
        // simple LRU cache
        mPeopleCache = new LinkedHashMap<String, PersonRecord>(MAX_PEOPLE_CACHE_SIZE+1, .75F, true) {
            @Override
            public boolean removeEldestEntry(Map.Entry eldest) {
              return size() > MAX_PEOPLE_CACHE_SIZE;
            }
        };

        mUserAddress = new Address(userEmail);

        mReferenceValue = PrefStore.getReferenceUid(ctx);
        if (mReferenceValue == null) {
          mReferenceValue = generateReferenceValue(userEmail);
          PrefStore.setReferenceUid(ctx, mReferenceValue);
        }

        mMarkAsRead = PrefStore.getMarkAsRead(ctx);
    }

    public ConversionResult cursorToMessages(final Cursor cursor, final int maxEntries) throws MessagingException {
        List<Message> messageList = new ArrayList<Message>(maxEntries);
        long maxDate = PrefStore.DEFAULT_MAX_SYNCED_DATE;
        final String[] columns = cursor.getColumnNames();

        while (messageList.size() < maxEntries && cursor.moveToNext()) {
            final long date = cursor.getLong(cursor.getColumnIndex(SmsConsts.DATE));
            if (date > maxDate) {
                maxDate = date;
            }

            Map<String, String> msgMap = new HashMap<String, String>(columns.length);
            for (int i = 0; i < columns.length; i++) {
              msgMap.put(columns[i], cursor.getString(i));
            }
            Message m = messageFromMap(msgMap);
            if (m != null) messageList.add(m);
        }

        ConversionResult result = new ConversionResult();
        result.maxDate = maxDate;
        result.messageList = messageList;
        return result;
    }

    private Message messageFromMap(Map<String, String> msgMap) throws MessagingException {
        Message msg = new MimeMessage();

        String address = msgMap.get(SmsConsts.ADDRESS);
        if (address == null || address.trim().length() == 0) {
           return null;
        }

        PersonRecord record = lookupPerson(address);
        msg.setSubject("SMS with " + record.name);

        TextBody body = new TextBody(msgMap.get(SmsConsts.BODY));

        int messageType = Integer.valueOf(msgMap.get(SmsConsts.TYPE));
        if (SmsConsts.MESSAGE_TYPE_INBOX == messageType) {
            // Received message
            msg.setFrom(record.address);
            msg.setRecipient(RecipientType.TO, mUserAddress);
        } else {
            // Sent message
            msg.setRecipient(RecipientType.TO, record.address);
            msg.setFrom(mUserAddress);
        }

        msg.setBody(body);

        try {
          Date then = new Date(Long.valueOf(msgMap.get(SmsConsts.DATE)));
          msg.setSentDate(then);
          msg.setInternalDate(then);
          msg.setHeader("Message-ID", createMessageId(then, address, messageType));
        } catch (NumberFormatException n) {
          Log.e(Consts.TAG, "error parsing date", n);
        }

        // Threading by person ID, not by thread ID. I think this value is more stable.
        msg.setHeader("References", String.format(REFERENCE_UID_TEMPLATE, mReferenceValue, record._id));
        msg.setHeader(Headers.ID, msgMap.get(SmsConsts.ID));
        msg.setHeader(Headers.ADDRESS, address);
        msg.setHeader(Headers.TYPE, msgMap.get(SmsConsts.TYPE));
        msg.setHeader(Headers.DATE, msgMap.get(SmsConsts.DATE));
        msg.setHeader(Headers.THREAD_ID, msgMap.get(SmsConsts.THREAD_ID));
        msg.setHeader(Headers.READ, msgMap.get(SmsConsts.READ));
        msg.setHeader(Headers.STATUS, msgMap.get(SmsConsts.STATUS));
        msg.setHeader(Headers.PROTOCOL, msgMap.get(SmsConsts.PROTOCOL));
        msg.setHeader(Headers.SERVICE_CENTER, msgMap.get(SmsConsts.SERVICE_CENTER));
        msg.setHeader(Headers.BACKUP_TIME, new Date().toGMTString());
        msg.setHeader(Headers.VERSION, PrefStore.getVersion(mContext, true));
        msg.setFlag(Flag.SEEN, mMarkAsRead);

        return msg;
    }

    /**
      * Create a message-id based on message date, phone number and message
      * type.
      */
    private String createMessageId(Date sent, String address, int type) {
      try {
        MessageDigest digest = java.security.MessageDigest.getInstance("MD5");

        digest.update(Long.toString(sent.getTime()).getBytes("UTF-8"));
        digest.update(address.getBytes("UTF-8"));
        digest.update(Integer.toString(type).getBytes("UTF-8"));

        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) {
          sb.append(String.format("%02x", b));
        }
        return String.format(MSG_ID_TEMPLATE, sb.toString());
      } catch (java.io.UnsupportedEncodingException e) {
        throw new RuntimeException(e);
      } catch (java.security.NoSuchAlgorithmException e) {
        throw new RuntimeException(e);
      }
    }

    /* Look up a person */
    private PersonRecord lookupPerson(String address) {
        if (!mPeopleCache.containsKey(address)) {
            Uri personUri = Uri.withAppendedPath(Phones.CONTENT_FILTER_URL, Uri.encode(address));
            Cursor c = mContext.getContentResolver().query(personUri, PHONE_PROJECTION, null, null, null);

            if (c != null && c.moveToFirst()) {
                long personId = c.getLong(c.getColumnIndex(Phones.PERSON_ID));
                String name   = c.getString(c.getColumnIndex(People.NAME));
                String number = c.getString(c.getColumnIndex(Phones.NUMBER));
                c.close();

                String primaryEmail = getPrimaryEmail(personId, number);

                PersonRecord record = new PersonRecord();
                record._id = String.valueOf(personId);
                record.name = name;
                record.address = new Address(primaryEmail, encodeDisplayName(name));

                mPeopleCache.put(address, record);
            } else {
                if (LOCAL_LOGV) Log.v(Consts.TAG, "Looked up unknown address: " + address);

                PersonRecord record = new PersonRecord();
                record = new PersonRecord();
                record._id = address;
                record.name = address;
                record.address = new Address(encodeLocal(address) + "@" + UNKNOWN_PERSON);

                mPeopleCache.put(address, record);
            }
        }
        return mPeopleCache.get(address);
    }

    private String getPrimaryEmail(final long personId, final String number) {
        if (personId <= 0) {
          return getUnknownEmail(number);
        }
        String primaryEmail = null;

        // Get all e-mail addresses for that person.
        Cursor c = mContext.getContentResolver().query(
                ContactMethods.CONTENT_EMAIL_URI,
                new String[] { ContactMethods.DATA },
                ContactMethods.PERSON_ID + " = ?", new String[] { String.valueOf(personId) },
                ContactMethods.ISPRIMARY + " DESC");

        // Loop over cursor and find a Gmail address for that person.
        // If there is none, pick first e-mail address.
        while (c.moveToNext()) {
            String e = c.getString(c.getColumnIndex(ContactMethods.DATA));
            if (primaryEmail == null) {
                primaryEmail = e;
            }
            if (isGmailAddress(e)) {
                primaryEmail = e;
                break;
            }
        }
        c.close();

        // Return found e-mail address or a dummy "unknown e-mail address"
        // if there is none.
        if (primaryEmail == null) {
          return getUnknownEmail(number);
        } else {
          return primaryEmail;
        }
    }

    private static String encodeLocal(String s) {
      return (s != null ? EncoderUtil.encodeAddressLocalPart(s) : null);
    }

    private static String encodeDisplayName(String s) {
      return (s != null ? EncoderUtil.encodeAddressDisplayName(s) : null);
    }

    private static String getUnknownEmail(String number) {
        String no = (number == null) ? UNKNOWN_NUMBER : number;
        return encodeLocal(no.trim()) + "@" + UNKNOWN_EMAIL;
    }

    /** Returns whether the given e-mail address is a Gmail address or not. */
    private static boolean isGmailAddress(String email) {
        return email.endsWith("gmail.com") || email.endsWith("googlemail.com");
    }

    private static String generateReferenceValue(String email) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < 24; i++) {
        sb.append(Integer.toString((int)(Math.random() * 35), 36));
      }
      return sb.toString();
    }

    public static class ConversionResult {
        public long maxDate;
        public List<Message> messageList;
    }

    private static class PersonRecord {
        String _id;
        String name;
        Address address;
    }
}
