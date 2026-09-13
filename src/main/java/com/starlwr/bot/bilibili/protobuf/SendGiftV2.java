package com.starlwr.bot.bilibili.protobuf;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * SEND_GIFT_V2 的 protobuf 结构及解析
 */
@Data
@AllArgsConstructor
public class SendGiftV2 {
    private long uid;
    private String uname;
    private String face;
    private int guardLevel;
    private BlindGift blindGift;
    private List<GiftItem> gifts;
    private WealthInfo wealthInfo;
    private UserInfo senderInfo;

    public static SendGiftV2 parseFrom(byte[] data) throws IOException {
        CodedInputStream input = CodedInputStream.newInstance(data);
        long uid = 0;
        String uname = "";
        String face = "";
        int guardLevel = 0;
        BlindGift blindGift = BlindGift.EMPTY;
        List<GiftItem> gifts = new ArrayList<>();
        WealthInfo wealthInfo = WealthInfo.EMPTY;
        UserInfo senderInfo = UserInfo.EMPTY;

        int tag;
        while ((tag = input.readTag()) != 0) {
            switch (WireFormat.getTagFieldNumber(tag)) {
                case 1 -> uid = input.readUInt64();
                case 2 -> uname = input.readStringRequireUtf8();
                case 3 -> face = input.readStringRequireUtf8();
                case 5 -> guardLevel = input.readUInt32();
                case 9 -> blindGift = BlindGift.parseFrom(input.readByteArray());
                case 10 -> gifts.add(GiftItem.parseFrom(input.readByteArray()));
                case 13 -> wealthInfo = WealthInfo.parseFrom(input.readByteArray());
                case 15 -> senderInfo = UserInfo.parseFrom(input.readByteArray());
                default -> input.skipField(tag);
            }
        }

        return new SendGiftV2(uid, uname, face, guardLevel, blindGift, List.copyOf(gifts), wealthInfo, senderInfo);
    }

    @Data
    @AllArgsConstructor
    public static class WealthInfo {
        private static final WealthInfo EMPTY = new WealthInfo(0);

        private int level;

        private static WealthInfo parseFrom(byte[] data) throws IOException {
            CodedInputStream input = CodedInputStream.newInstance(data);
            int level = 0;

            int tag;
            while ((tag = input.readTag()) != 0) {
                if (WireFormat.getTagFieldNumber(tag) == 1) {
                    level = input.readUInt32();
                } else {
                    input.skipField(tag);
                }
            }
            return new WealthInfo(level);
        }
    }

    @Data
    @AllArgsConstructor
    public static class UserInfo {
        private static final UserInfo EMPTY = new UserInfo(Medal.EMPTY);

        private Medal medal;

        private static UserInfo parseFrom(byte[] data) throws IOException {
            CodedInputStream input = CodedInputStream.newInstance(data);
            Medal medal = Medal.EMPTY;

            int tag;
            while ((tag = input.readTag()) != 0) {
                if (WireFormat.getTagFieldNumber(tag) == 3) {
                    medal = Medal.parseFrom(input.readByteArray());
                } else {
                    input.skipField(tag);
                }
            }
            return new UserInfo(medal);
        }
    }

    @Data
    @AllArgsConstructor
    public static class Medal {
        private static final Medal EMPTY = new Medal("", 0, false, 0, "");

        private String name;
        private int level;
        private boolean lighted;
        private long ruid;
        private String guardIcon;

        private static Medal parseFrom(byte[] data) throws IOException {
            CodedInputStream input = CodedInputStream.newInstance(data);
            String name = "";
            int level = 0;
            boolean lighted = false;
            long ruid = 0;
            String guardIcon = "";

            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> name = input.readStringRequireUtf8();
                    case 2 -> level = input.readUInt32();
                    case 9 -> lighted = input.readBool();
                    case 10 -> ruid = input.readUInt64();
                    case 13 -> guardIcon = input.readStringRequireUtf8();
                    default -> input.skipField(tag);
                }
            }
            return new Medal(name, level, lighted, ruid, guardIcon);
        }

        public boolean isPresent() {
            return ruid != 0;
        }
    }

    @Data
    @AllArgsConstructor
    public static class BlindGift {
        private static final BlindGift EMPTY = new BlindGift(0, "", 0);

        private long id;
        private String name;
        private long price;

        private static BlindGift parseFrom(byte[] data) throws IOException {
            CodedInputStream input = CodedInputStream.newInstance(data);
            long id = 0;
            String name = "";
            long price = 0;

            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 2 -> id = input.readUInt64();
                    case 3 -> name = input.readStringRequireUtf8();
                    case 6 -> price = input.readUInt64();
                    default -> input.skipField(tag);
                }
            }
            return new BlindGift(id, name, price);
        }

        public boolean isPresent() {
            return !name.isBlank() || price > 0;
        }
    }

    @Data
    @AllArgsConstructor
    public static class GiftItem {
        private long id;
        private String name;
        private int count;
        private long discountPrice;
        private String coinType;
        private long timestamp;
        private String imageUrl;

        private static GiftItem parseFrom(byte[] data) throws IOException {
            CodedInputStream input = CodedInputStream.newInstance(data);
            long id = 0;
            String name = "";
            int count = 0;
            long discountPrice = 0;
            String coinType = "";
            long timestamp = 0;
            String imageUrl = "";

            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> id = input.readUInt64();
                    case 2 -> name = input.readStringRequireUtf8();
                    case 3 -> count = input.readUInt32();
                    case 6 -> discountPrice = input.readUInt64();
                    case 8 -> coinType = input.readStringRequireUtf8();
                    case 10 -> timestamp = input.readUInt64();
                    case 35 -> imageUrl = GiftMaterial.parseImageUrl(input.readByteArray());
                    default -> input.skipField(tag);
                }
            }
            return new GiftItem(id, name, count, discountPrice, coinType, timestamp, imageUrl);
        }
    }

    private static final class GiftMaterial {
        private GiftMaterial() {
        }

        private static String parseImageUrl(byte[] data) throws IOException {
            CodedInputStream input = CodedInputStream.newInstance(data);
            String imageUrl = "";
            int tag;
            while ((tag = input.readTag()) != 0) {
                if (WireFormat.getTagFieldNumber(tag) == 1) {
                    imageUrl = input.readStringRequireUtf8();
                } else {
                    input.skipField(tag);
                }
            }
            return imageUrl;
        }
    }
}
