/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Lo que el código PDU de AOSP (Apache-2.0, ver pdu/NOTICE) usa de
 * com.android.messaging.util.ContentType, escrito de nuevo.
 */
package dev.liquidmessages.mms;

public final class ContentType {
    public static final String MMS_MULTIPART_MIXED = "application/vnd.wap.multipart.mixed";
    public static final String MMS_MULTIPART_RELATED = "application/vnd.wap.multipart.related";
    public static final String MMS_MULTIPART_ALTERNATIVE = "application/vnd.wap.multipart.alternative";

    private ContentType() {}

    public static boolean equalsIgnoreCase(String a, String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b);
    }
}
