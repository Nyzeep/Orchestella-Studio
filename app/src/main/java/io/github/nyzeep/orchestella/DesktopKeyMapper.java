package io.github.nyzeep.orchestella;

/** Android key codes mapped to Linux input codes without assuming a host keyboard layout. */
public final class DesktopKeyMapper {
    private static final int[] LETTERS = {30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50,
            49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44};
    private DesktopKeyMapper() { }

    public static int linuxCode(int androidCode) {
        if (androidCode >= 29 && androidCode <= 54) return LETTERS[androidCode - 29];
        if (androidCode == 7) return 11;
        if (androidCode >= 8 && androidCode <= 16) return androidCode - 6;
        if (androidCode >= 131 && androidCode <= 140) return 59 + androidCode - 131;
        return switch (androidCode) {
            case 19 -> 103; case 20 -> 108; case 21 -> 105; case 22 -> 106;
            case 55 -> 51; case 56 -> 52; case 57 -> 56; case 58 -> 100;
            case 59 -> 42; case 60 -> 54; case 61 -> 15; case 62 -> 57;
            case 66 -> 28; case 67 -> 14; case 68 -> 41; case 69 -> 12;
            case 70 -> 13; case 71 -> 26; case 72 -> 27; case 73 -> 43;
            case 74 -> 39; case 75 -> 40; case 76 -> 53; case 92 -> 104;
            case 93 -> 109; case 111 -> 1; case 112 -> 111;
            case 113 -> 29; case 114 -> 97; case 115 -> 58;
            case 117 -> 125; case 118 -> 126; case 122 -> 102;
            case 123 -> 107; case 124 -> 110; case 141 -> 87; case 142 -> 88;
            default -> 0;
        };
    }

    public static int modifiers(int androidMeta) {
        int result = 0;
        if ((androidMeta & 1) != 0) result |= 1;
        if ((androidMeta & 65536) != 0) result |= 2;
        if ((androidMeta & 2) != 0) result |= 4;
        if ((androidMeta & 1048576) != 0) result |= 8;
        return result;
    }
}
