package com.starlwr.bot.bilibili.protobuf;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.IOException;

/**
 * INTERACT_WORD_V2 的 protobuf 结构及解析
 */
@Data
@AllArgsConstructor
public class InteractWordV2 {
    private long uid;
    private String uname;
    private int msgType;
    private long timestamp;
    private FansMedalInfo fansMedal;
    private boolean spread;
    private String spreadDesc;
    private int privilegeType;
    private UserInfo userInfo;

    public static InteractWordV2 parseFrom(byte[] data) throws IOException {
        CodedInputStream input = CodedInputStream.newInstance(data);
        long uid = 0;
        String uname = "";
        int msgType = 0;
        long timestamp = 0;
        FansMedalInfo fansMedal = FansMedalInfo.EMPTY;
        boolean spread = false;
        String spreadDesc = "";
        int privilegeType = 0;
        UserInfo userInfo = UserInfo.EMPTY;

        int tag;
        while ((tag = input.readTag()) != 0) {
            switch (WireFormat.getTagFieldNumber(tag)) {
                case 1 -> uid = input.readUInt64();
                case 2 -> uname = input.readStringRequireUtf8();
                case 5 -> msgType = input.readUInt32();
                case 7 -> timestamp = input.readUInt64();
                case 9 -> fansMedal = FansMedalInfo.parseFrom(input.readByteArray());
                case 10 -> spread = input.readUInt64() != 0;
                case 13 -> spreadDesc = input.readStringRequireUtf8();
                case 16 -> privilegeType = input.readUInt32();
                case 22 -> userInfo = UserInfo.parseFrom(input.readByteArray());
                default -> input.skipField(tag);
            }
        }

        return new InteractWordV2(uid, uname, msgType, timestamp, fansMedal, spread, spreadDesc, privilegeType, userInfo);
    }

    @Data
    @AllArgsConstructor
    public static class FansMedalInfo {
        private static final FansMedalInfo EMPTY = new FansMedalInfo(0, 0, "", false);

        private long targetId;
        private int level;
        private String name;
        private boolean lighted;

        private static FansMedalInfo parseFrom(byte[] data) throws IOException {
            CodedInputStream input = CodedInputStream.newInstance(data);
            long targetId = 0;
            int level = 0;
            String name = "";
            boolean lighted = false;

            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 1 -> targetId = input.readInt64();
                    case 2 -> level = input.readInt32();
                    case 3 -> name = input.readStringRequireUtf8();
                    case 8 -> lighted = input.readInt64() == 1;
                    default -> input.skipField(tag);
                }
            }
            return new FansMedalInfo(targetId, level, name, lighted);
        }

        public boolean isPresent() {
            return targetId != 0;
        }
    }

    @Data
    @AllArgsConstructor
    public static class UserInfo {
        private static final UserInfo EMPTY = new UserInfo(UserBase.EMPTY, null);

        private UserBase base;
        private Integer wealthLevel;

        private static UserInfo parseFrom(byte[] data) throws IOException {
            CodedInputStream input = CodedInputStream.newInstance(data);
            UserBase base = UserBase.EMPTY;
            Integer wealthLevel = null;

            int tag;
            while ((tag = input.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tag)) {
                    case 2 -> base = UserBase.parseFrom(input.readByteArray());
                    case 4 -> wealthLevel = WealthInfo.parseLevel(input.readByteArray());
                    default -> input.skipField(tag);
                }
            }
            return new UserInfo(base, wealthLevel);
        }
    }

    @Data
    @AllArgsConstructor
    public static class UserBase {
        private static final UserBase EMPTY = new UserBase("");

        private String face;

        private static UserBase parseFrom(byte[] data) throws IOException {
            CodedInputStream input = CodedInputStream.newInstance(data);
            String face = "";

            int tag;
            while ((tag = input.readTag()) != 0) {
                if (WireFormat.getTagFieldNumber(tag) == 2) {
                    face = input.readStringRequireUtf8();
                } else {
                    input.skipField(tag);
                }
            }
            return new UserBase(face);
        }
    }

    private static final class WealthInfo {
        private WealthInfo() {
        }

        private static int parseLevel(byte[] data) throws IOException {
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
            return level;
        }
    }
}
