import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * ViaVersion / ViaBackwards / ViaRewind 플러그인 jar 를 안드로이드에서 쓸 수 있게 고치는 도구.
 *
 * 1) 안드로이드에 없는 JDK API 호출을 kr.jamsuham.compat 의 대체 메서드로 바꾼다.
 *    - Runtime.version() / Runtime.Version.feature()
 *    - StringBuilder.isEmpty(), String.formatted(), String.lines()
 *    - List.toArray(IntFunction), Stream.toList()
 *    - Optional.isEmpty(), Optional.orElseThrow(), Objects.requireNonNullElse(Get)
 * 2) Bukkit / Velocity 같은 서버 플랫폼 전용 클래스와 META-INF/versions 를 뺀다.
 *
 * 사용법 (JDK 11 이상): java tools/ViaPatcher.java 입력.jar 출력.jar
 * 의존성 없이 클래스 파일의 상수 풀과 바이트코드만 직접 수정한다.
 */
public class ViaPatcher {

    static final String SHIM_VERSION = "kr/jamsuham/compat/RuntimeVersion";
    static final String SHIM = "kr/jamsuham/compat/Compat";

    /** 원래 메서드 → 대체 정적 메서드. key = owner.name+desc */
    static final Map<String, String[]> REDIRECTS = new LinkedHashMap<>();

    static {
        // 정적 → 정적
        REDIRECTS.put("java/lang/Runtime.version()L" + SHIM_VERSION + ";", new String[]{SHIM_VERSION, "version", "()L" + SHIM_VERSION + ";"});
        REDIRECTS.put("java/util/Objects.requireNonNullElse(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
            new String[]{SHIM, "requireNonNullElse", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"});
        REDIRECTS.put("java/util/Objects.requireNonNullElseGet(Ljava/lang/Object;Ljava/util/function/Supplier;)Ljava/lang/Object;",
            new String[]{SHIM, "requireNonNullElseGet", "(Ljava/lang/Object;Ljava/util/function/Supplier;)Ljava/lang/Object;"});
        // 인스턴스 → 정적 (수신 객체가 첫 번째 인자가 된다)
        REDIRECTS.put("java/lang/StringBuilder.isEmpty()Z", new String[]{SHIM, "isEmpty", "(Ljava/lang/StringBuilder;)Z"});
        REDIRECTS.put("java/lang/String.formatted([Ljava/lang/Object;)Ljava/lang/String;",
            new String[]{SHIM, "formatted", "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;"});
        REDIRECTS.put("java/lang/String.lines()Ljava/util/stream/Stream;", new String[]{SHIM, "lines", "(Ljava/lang/String;)Ljava/util/stream/Stream;"});
        REDIRECTS.put("java/util/List.toArray(Ljava/util/function/IntFunction;)[Ljava/lang/Object;",
            new String[]{SHIM, "toArray", "(Ljava/util/Collection;Ljava/util/function/IntFunction;)[Ljava/lang/Object;"});
        REDIRECTS.put("java/util/Collection.toArray(Ljava/util/function/IntFunction;)[Ljava/lang/Object;",
            new String[]{SHIM, "toArray", "(Ljava/util/Collection;Ljava/util/function/IntFunction;)[Ljava/lang/Object;"});
        REDIRECTS.put("java/util/Set.toArray(Ljava/util/function/IntFunction;)[Ljava/lang/Object;",
            new String[]{SHIM, "toArray", "(Ljava/util/Collection;Ljava/util/function/IntFunction;)[Ljava/lang/Object;"});
        REDIRECTS.put("java/util/stream/Stream.toList()Ljava/util/List;", new String[]{SHIM, "toList", "(Ljava/util/stream/Stream;)Ljava/util/List;"});
        REDIRECTS.put("java/util/Optional.isEmpty()Z", new String[]{SHIM, "isEmpty", "(Ljava/util/Optional;)Z"});
        REDIRECTS.put("java/util/Optional.orElseThrow()Ljava/lang/Object;", new String[]{SHIM, "orElseThrow", "(Ljava/util/Optional;)Ljava/lang/Object;"});
    }

    static final String[] SKIP_PREFIXES = {
        "META-INF/versions/",
        "com/viaversion/viaversion/bukkit/",
        "com/viaversion/viaversion/velocity/",
        "com/viaversion/viaversion/bungee/",
        "com/viaversion/viaversion/sponge/",
        "com/viaversion/viaversion/fabric/",
        "com/viaversion/viabackwards/bukkit/",
        "com/viaversion/viabackwards/velocity/",
        "com/viaversion/viabackwards/bungee/",
        "com/viaversion/viabackwards/sponge/",
        "com/viaversion/viabackwards/fabric/",
        "com/viaversion/viarewind/bukkit/",
        "com/viaversion/viarewind/velocity/",
        "com/viaversion/viarewind/bungee/",
        "com/viaversion/viarewind/sponge/",
        "com/viaversion/viarewind/fabric/",
    };

    static final Set<String> SKIP_FILES = Set.of("plugin.yml", "paper-plugin.yml", "bungee.yml", "velocity-plugin.json", "fabric.mod.json",
        "META-INF/sponge_plugins.json", "LICENSE");

    static int patchedClasses = 0;
    static int patchedCalls = 0;

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("사용법: java ViaPatcher.java <입력.jar> <출력.jar>");
            System.exit(2);
        }
        Path in = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        int skipped = 0;
        int total = 0;
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(Files.newInputStream(in)));
             ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(out)))) {
            Set<String> written = new HashSet<>();
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                if (e.isDirectory()) continue;
                if (shouldSkip(name)) {
                    skipped++;
                    continue;
                }
                byte[] data = zin.readAllBytes();
                if (name.endsWith(".class")) {
                    byte[] patched = patchClass(data);
                    if (patched != data) patchedClasses++;
                    data = patched;
                }
                if (!written.add(name)) continue;
                total++;
                ZipEntry ne = new ZipEntry(name);
                ne.setTime(315532800000L);
                zout.putNextEntry(ne);
                zout.write(data);
                zout.closeEntry();
            }
        }
        System.out.printf("%s -> %s : 항목 %d개, 제외 %d개, 수정된 클래스 %d개, 바뀐 호출 %d곳%n",
            in.getFileName(), out.getFileName(), total, skipped, patchedClasses, patchedCalls);
    }

    static boolean shouldSkip(String name) {
        for (String p : SKIP_PREFIXES) if (name.startsWith(p)) return true;
        if (SKIP_FILES.contains(name)) return true;
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.startsWith("META-INF/") && (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA"));
    }

    // ---------------------------------------------------------------- class file

    static final class Cp {
        int tag;
        String utf;     // tag 1
        byte[] raw;     // 그 외 태그의 원본 바이트 (태그 제외)
        int a, b;       // class/nat/ref/handle 인덱스

        Cp copy() {
            Cp c = new Cp();
            c.tag = tag; c.utf = utf; c.raw = raw; c.a = a; c.b = b;
            return c;
        }
    }

    static byte[] patchClass(byte[] data) throws IOException {
        // 빠른 확인: 관련 문자열이 없으면 그대로 둔다
        String latin = new String(data, StandardCharsets.ISO_8859_1);
        boolean relevant = latin.contains("Runtime$Version");
        if (!relevant) {
            for (String k : REDIRECTS.keySet()) {
                String owner = k.substring(0, k.indexOf('.'));
                String member = k.substring(k.indexOf('.') + 1, k.indexOf('('));
                if (latin.contains(owner) && latin.contains(member)) { relevant = true; break; }
            }
        }
        if (!relevant) return data;

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        int magic = in.readInt();
        if (magic != 0xCAFEBABE) return data;
        int minor = in.readUnsignedShort();
        int major = in.readUnsignedShort();
        int count = in.readUnsignedShort();
        List<Cp> cp = new ArrayList<>(Collections.nCopies(count, (Cp) null));
        for (int i = 1; i < count; i++) {
            Cp c = new Cp();
            c.tag = in.readUnsignedByte();
            switch (c.tag) {
                case 1 -> c.utf = in.readUTF();
                case 3, 4 -> { c.raw = new byte[4]; in.readFully(c.raw); }
                case 5, 6 -> { c.raw = new byte[8]; in.readFully(c.raw); }
                case 7, 8, 16, 19, 20 -> c.a = in.readUnsignedShort();
                case 9, 10, 11, 12, 17, 18 -> { c.a = in.readUnsignedShort(); c.b = in.readUnsignedShort(); }
                case 15 -> { c.a = in.readUnsignedByte(); c.b = in.readUnsignedShort(); }
                default -> throw new IOException("알 수 없는 상수 태그 " + c.tag);
            }
            cp.set(i, c);
            if (c.tag == 5 || c.tag == 6) i++;
        }
        byte[] rest = in.readAllBytes();
        boolean changed = false;

        // 1) Runtime$Version 이름 바꾸기
        for (Cp c : cp) {
            if (c != null && c.tag == 1 && c.utf.contains("java/lang/Runtime$Version")) {
                c.utf = c.utf.replace("java/lang/Runtime$Version", SHIM_VERSION);
                changed = true;
            }
        }

        // 2) 메서드 참조 바꾸기
        Map<Integer, Integer> virtualToStatic = new HashMap<>(); // 옛 인덱스 -> 새 Methodref 인덱스
        int originalCount = cp.size();
        for (int i = 1; i < originalCount; i++) {
            Cp c = cp.get(i);
            if (c == null || (c.tag != 10 && c.tag != 11)) continue;
            String owner = cp.get(cp.get(c.a).a).utf;
            Cp nat = cp.get(c.b);
            String key = owner + "." + cp.get(nat.a).utf + cp.get(nat.b).utf;
            String[] target = REDIRECTS.get(key);
            if (target == null) continue;
            int classIdx = classIndex(cp, target[0]);
            int natIdx = natIndex(cp, target[1], target[2]);
            boolean isStaticOriginal = key.startsWith("java/lang/Runtime.version") || key.startsWith("java/util/Objects.");
            if (isStaticOriginal) {
                c.tag = 10;
                c.a = classIdx;
                c.b = natIdx;
            } else {
                Cp m = new Cp();
                m.tag = 10; m.a = classIdx; m.b = natIdx;
                cp.add(m);
                virtualToStatic.put(i, cp.size() - 1);
            }
            changed = true;
        }
        if (!changed) return data;

        // MethodHandle 상수(메서드 참조 람다)도 정적 호출로 바꾼다
        for (int i = 1; i < originalCount; i++) {
            Cp c = cp.get(i);
            if (c != null && c.tag == 15 && virtualToStatic.containsKey(c.b) && (c.a == 5 || c.a == 9)) {
                c.a = 6; // REF_invokeStatic
                c.b = virtualToStatic.get(c.b);
                patchedCalls++;
            }
        }

        if (cp.size() > 0xFFFF) throw new IOException("상수 풀이 너무 큽니다");

        // 3) 바이트코드의 invokevirtual / invokeinterface 를 invokestatic 으로
        if (!virtualToStatic.isEmpty()) {
            patchCode(rest, cp, virtualToStatic);
        }

        ByteArrayOutputStream bout = new ByteArrayOutputStream(data.length + 256);
        DataOutputStream out = new DataOutputStream(bout);
        out.writeInt(magic);
        out.writeShort(minor);
        out.writeShort(major);
        out.writeShort(cp.size());
        for (int i = 1; i < cp.size(); i++) {
            Cp c = cp.get(i);
            if (c == null) continue; // long/double 두 번째 칸
            out.writeByte(c.tag);
            switch (c.tag) {
                case 1 -> out.writeUTF(c.utf);
                case 3, 4, 5, 6 -> out.write(c.raw);
                case 7, 8, 16, 19, 20 -> out.writeShort(c.a);
                case 9, 10, 11, 12, 17, 18 -> { out.writeShort(c.a); out.writeShort(c.b); }
                case 15 -> { out.writeByte(c.a); out.writeShort(c.b); }
                default -> throw new IOException("tag " + c.tag);
            }
        }
        out.write(rest);
        out.flush();
        return bout.toByteArray();
    }

    static int utfIndex(List<Cp> cp, String s) {
        for (int i = 1; i < cp.size(); i++) {
            Cp c = cp.get(i);
            if (c != null && c.tag == 1 && c.utf.equals(s)) return i;
        }
        Cp c = new Cp();
        c.tag = 1; c.utf = s;
        cp.add(c);
        return cp.size() - 1;
    }

    static int classIndex(List<Cp> cp, String name) {
        int u = utfIndex(cp, name);
        for (int i = 1; i < cp.size(); i++) {
            Cp c = cp.get(i);
            if (c != null && c.tag == 7 && c.a == u) return i;
        }
        Cp c = new Cp();
        c.tag = 7; c.a = u;
        cp.add(c);
        return cp.size() - 1;
    }

    static int natIndex(List<Cp> cp, String name, String desc) {
        int n = utfIndex(cp, name);
        int d = utfIndex(cp, desc);
        for (int i = 1; i < cp.size(); i++) {
            Cp c = cp.get(i);
            if (c != null && c.tag == 12 && c.a == n && c.b == d) return i;
        }
        Cp c = new Cp();
        c.tag = 12; c.a = n; c.b = d;
        cp.add(c);
        return cp.size() - 1;
    }

    /** rest = access_flags 부터 클래스 파일 끝까지. Code 속성을 찾아 그 안을 수정한다. */
    static void patchCode(byte[] rest, List<Cp> cp, Map<Integer, Integer> map) throws IOException {
        int p = 6; // access, this, super
        int ifaces = u2(rest, p); p += 2 + ifaces * 2;
        int fields = u2(rest, p); p += 2;
        for (int f = 0; f < fields; f++) {
            p += 6;
            int ac = u2(rest, p); p += 2;
            for (int a = 0; a < ac; a++) { p += 2; int len = u4(rest, p); p += 4 + len; }
        }
        int methods = u2(rest, p); p += 2;
        for (int m = 0; m < methods; m++) {
            p += 6;
            int ac = u2(rest, p); p += 2;
            for (int a = 0; a < ac; a++) {
                int nameIdx = u2(rest, p);
                int len = u4(rest, p + 2);
                int body = p + 6;
                if ("Code".equals(cp.get(nameIdx).utf)) {
                    int codeLen = u4(rest, body + 4);
                    int codeStart = body + 8;
                    patchInstructions(rest, codeStart, codeLen, map);
                }
                p = body + len;
            }
        }
    }

    static void patchInstructions(byte[] b, int start, int len, Map<Integer, Integer> map) throws IOException {
        int pc = 0;
        while (pc < len) {
            int at = start + pc;
            int op = b[at] & 0xFF;
            int size;
            switch (op) {
                case 0xB6, 0xB7, 0xB8 -> { // invokevirtual / special / static
                    int idx = u2(b, at + 1);
                    Integer to = map.get(idx);
                    if (to != null && op == 0xB6) {
                        b[at] = (byte) 0xB8;
                        b[at + 1] = (byte) (to >> 8);
                        b[at + 2] = (byte) (int) to;
                        patchedCalls++;
                    }
                    size = 3;
                }
                case 0xB9 -> { // invokeinterface (5바이트) -> invokestatic(3) + nop nop
                    int idx = u2(b, at + 1);
                    Integer to = map.get(idx);
                    if (to != null) {
                        b[at] = (byte) 0xB8;
                        b[at + 1] = (byte) (to >> 8);
                        b[at + 2] = (byte) (int) to;
                        b[at + 3] = 0;
                        b[at + 4] = 0;
                        patchedCalls++;
                    }
                    size = 5;
                }
                case 0xAA -> { // tableswitch
                    int pad = (4 - ((pc + 1) % 4)) % 4;
                    int base = at + 1 + pad;
                    int low = u4(b, base + 4);
                    int high = u4(b, base + 8);
                    size = 1 + pad + 12 + (high - low + 1) * 4;
                }
                case 0xAB -> { // lookupswitch
                    int pad = (4 - ((pc + 1) % 4)) % 4;
                    int base = at + 1 + pad;
                    int npairs = u4(b, base + 4);
                    size = 1 + pad + 8 + npairs * 8;
                }
                case 0xC4 -> size = ((b[at + 1] & 0xFF) == 0x84) ? 6 : 4; // wide
                default -> size = opSize(op);
            }
            if (size <= 0) throw new IOException("알 수 없는 opcode 0x" + Integer.toHexString(op));
            pc += size;
        }
    }

    static int opSize(int op) {
        if (op <= 0x0F) return 1;
        if (op == 0x10) return 2;
        if (op == 0x11) return 3;
        if (op == 0x12) return 2;
        if (op == 0x13 || op == 0x14) return 3;
        if (op >= 0x15 && op <= 0x19) return 2;
        if (op >= 0x1A && op <= 0x35) return 1;
        if (op >= 0x36 && op <= 0x3A) return 2;
        if (op >= 0x3B && op <= 0x83) return 1;
        if (op == 0x84) return 3;
        if (op >= 0x85 && op <= 0x98) return 1;
        if (op >= 0x99 && op <= 0xA8) return 3;
        if (op == 0xA9) return 2;
        if (op >= 0xAC && op <= 0xB1) return 1;
        if (op >= 0xB2 && op <= 0xB5) return 3;
        if (op == 0xBA) return 5;
        if (op == 0xBB) return 3;
        if (op == 0xBC) return 2;
        if (op == 0xBD) return 3;
        if (op == 0xBE || op == 0xBF) return 1;
        if (op == 0xC0 || op == 0xC1) return 3;
        if (op == 0xC2 || op == 0xC3) return 1;
        if (op == 0xC5) return 4;
        if (op == 0xC6 || op == 0xC7) return 3;
        if (op == 0xC8 || op == 0xC9) return 5;
        if (op == 0xCA) return 1;
        return -1;
    }

    static int u2(byte[] b, int p) {
        return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
    }

    static int u4(byte[] b, int p) {
        return ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
    }
}
