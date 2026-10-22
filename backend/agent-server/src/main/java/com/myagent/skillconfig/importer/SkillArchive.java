package com.myagent.skillconfig.importer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.common.BizException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 把仓库归档拆成「一个或多个标准 Skill」——纯 Java，不依赖 AgentScope。
 *
 * <p>拆包规则（对齐官方布局，实测 {@code anthropics/skills} 这类技能包就是这么排的）：
 *
 * <ul>
 *   <li>任何层级下名为 {@code SKILL.md} 的文件 = 一个技能的根，它的**父目录**就是技能根目录；
 *       根目录下的其它文件全部是它的资源（{@code scripts/}、{@code references/}、{@code assets/}…）
 *   <li>仓库 zip 顶层若只有一个目录（GitHub archive 的 {@code repo-main/}），先整体剥掉；
 *   <li>嵌套技能不互相吞并：内层 {@code SKILL.md} 所在子树只归内层
 *   <li>二进制资源按官方 {@code MarketplaceStager} 的约定以 {@code base64:} 前缀存文本
 * </ul>
 *
 * <p>「技能包」支持（2026-10-22 追加）：
 *
 * <ul>
 *   <li><b>归档格式</b>按文件头嗅探：zip / tar.gz / tar 都认（不看扩展名，{@code .skill} 这种自造后缀也能装）
 *   <li><b>子目录链接</b>：{@code .../tree/main/skills/pdf} 只保留该子树下的技能
 *   <li><b>包内再套包</b>：仓库里分发 {@code dist/foo.zip} / {@code foo.skill} 时，解开一层，
 *       里面的 SKILL.md 照样能识别（只解一层，防 zip 炸弹套娃）
 *   <li><b>插件分组</b>：读 {@code .claude-plugin/marketplace.json} 的 {@code plugins[].skills}，
 *       把每个技能标上所属的包名，界面上就能「按包装」
 * </ul>
 *
 * <p>安全与体量：拒绝绝对路径与 {@code ..}（zip-slip）、跳过 {@code __MACOSX/}、{@code .git/}、
 * {@code .DS_Store}，并对单个文件 / 总量 / 条目数设上限——仓库内容是**外部不可信输入**。
 */
public final class SkillArchive {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 嵌套归档最多解几层（防套娃）。 */
    private static final int MAX_NESTED_DEPTH = 1;

    private SkillArchive() {}

    /** 解包限制。默认值面向「技能仓库」这种体量（实测 anthropics/skills 全仓 4.3MB）。 */
    public record Limits(
            long maxArchiveBytes, int maxEntries, long maxFileBytes, long maxTotalBytes, int maxResourcesPerSkill) {

        public static Limits defaults() {
            return new Limits(20L * 1024 * 1024, 2000, 1024 * 1024, 40L * 1024 * 1024, 200);
        }
    }

    /**
     * 一个从归档里拆出来的技能。
     *
     * @param root 它在仓库里的目录（{@code skills/pdf}），展示与选择都用它
     * @param pack 所属的技能包（来自 {@code .claude-plugin/marketplace.json}），没有则为 null
     * @param markdown SKILL.md 原文（含 frontmatter，交给官方解析器）
     * @param resources 相对技能根目录的路径 → 内容（二进制已 base64）
     */
    public record Bundle(String root, String pack, String markdown, Map<String, String> resources) {

        public int resourceCount() {
            return resources.size();
        }

        public long resourceBytes() {
            long total = 0;
            for (String content : resources.values()) {
                total += content.startsWith("base64:")
                        ? (content.length() - 7) * 3L / 4
                        : content.getBytes(StandardCharsets.UTF_8).length;
            }
            return total;
        }
    }

    /** 归档里的一个文件。 */
    private record Entry(String path, byte[] content) {}

    /** 解包：不限子目录。 */
    public static List<Bundle> read(byte[] archiveBytes, Limits limits) {
        return read(archiveBytes, null, limits);
    }

    /**
     * 解包。
     *
     * @param subPath 只保留该子目录下的技能（{@code skills/pdf}）；null/空 = 全仓库
     * @throws BizException 归档过大 / 条目过多 / 单个文件超限 / 不是可识别的归档
     */
    public static List<Bundle> read(byte[] archiveBytes, String subPath, Limits limits) {
        if (archiveBytes == null || archiveBytes.length == 0) {
            throw BizException.badRequest("归档内容为空");
        }
        if (archiveBytes.length > limits.maxArchiveBytes()) {
            throw BizException.badRequest("归档超过上限 " + (limits.maxArchiveBytes() / 1024 / 1024) + "MB");
        }
        List<Entry> entries = unpack(archiveBytes, limits);
        entries = stripSingleRoot(entries);
        entries = unpackNested(entries, limits);
        String prefix = normalizeSubPath(subPath);
        if (!prefix.isEmpty()) {
            entries = filterBySubPath(entries, prefix);
        }
        Map<String, String> packs = readPackManifest(entries);
        return toBundles(entries, limits, packs);
    }

    // ------------------------------------------------------------------ 解包（zip / tar.gz / tar）

    private static List<Entry> unpack(byte[] bytes, Limits limits) {
        if (isZip(bytes)) {
            return unzip(new ZipInputStream(new ByteArrayInputStream(bytes)), limits);
        }
        if (isGzip(bytes)) {
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                return readTar(in, limits);
            } catch (IOException ex) {
                throw BizException.badRequest("tar.gz 解压失败：" + ex.getMessage());
            }
        }
        if (isTar(bytes)) {
            return readTar(new ByteArrayInputStream(bytes), limits);
        }
        throw BizException.badRequest("无法识别的归档格式（支持 zip / tar.gz / tar）");
    }

    static boolean isZip(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4;
    }

    static boolean isGzip(byte[] bytes) {
        return bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B;
    }

    static boolean isTar(byte[] bytes) {
        return bytes.length >= 262
                && new String(bytes, 257, 5, StandardCharsets.US_ASCII).equals("ustar");
    }

    private static List<Entry> unzip(ZipInputStream in, Limits limits) {
        List<Entry> entries = new ArrayList<>();
        long total = 0;
        try (in) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String raw = entry.getName();
                if (raw == null || raw.isBlank() || entry.isDirectory()) {
                    continue;
                }
                String path = normalize(raw);
                if (path == null || isJunk(path)) {
                    continue;
                }
                byte[] content = readLimited(in, limits.maxFileBytes(), path);
                total = accumulate(total, content.length, limits);
                entries.add(new Entry(path, content));
                if (entries.size() > limits.maxEntries()) {
                    throw BizException.badRequest("归档条目超过上限 " + limits.maxEntries() + " 个");
                }
            }
        } catch (IOException ex) {
            throw BizException.badRequest("不是合法的 zip 归档：" + ex.getMessage());
        }
        requireNotEmpty(entries);
        return entries;
    }

    /** 最小 ustar 解析（技能仓库的 tar 都是普通文件，够用；不支持稀疏文件/长名扩展）。 */
    private static List<Entry> readTar(InputStream in, Limits limits) {
        List<Entry> entries = new ArrayList<>();
        long total = 0;
        try {
            byte[] header = new byte[512];
            while (true) {
                int read = readFully(in, header, 512);
                if (read < 512) {
                    break;
                }
                // 连续两个全零块 = 归档结束
                boolean allZero = true;
                for (byte b : header) {
                    if (b != 0) {
                        allZero = false;
                        break;
                    }
                }
                if (allZero) {
                    break;
                }
                String name = new String(header, 0, 100, StandardCharsets.UTF_8).replaceAll("\0.*$", "");
                String prefix = new String(header, 345, 155, StandardCharsets.UTF_8).replaceAll("\0.*$", "");
                long size = parseOctal(header, 124, 12);
                char type = (char) header[156];
                boolean regular = type == '0' || type == 0;

                byte[] content = new byte[(int) Math.min(size, Integer.MAX_VALUE)];
                int got = readFully(in, content, content.length);
                if (got < content.length) {
                    throw BizException.badRequest("tar 归档不完整");
                }
                // 跳过填充到 512 的整数倍
                long padding = (512 - (size % 512)) % 512;
                if (padding > 0) {
                    readFully(in, new byte[(int) padding], (int) padding);
                }
                if (!regular) {
                    continue;
                }
                String raw = prefix.isEmpty() ? name : prefix + "/" + name;
                String path = normalize(raw);
                if (path == null || isJunk(path)) {
                    continue;
                }
                if (content.length > limits.maxFileBytes()) {
                    throw BizException.badRequest(
                            "文件超过上限 " + (limits.maxFileBytes() / 1024) + "KB：" + path);
                }
                total = accumulate(total, content.length, limits);
                entries.add(new Entry(path, content));
                if (entries.size() > limits.maxEntries()) {
                    throw BizException.badRequest("归档条目超过上限 " + limits.maxEntries() + " 个");
                }
            }
        } catch (IOException ex) {
            throw BizException.badRequest("不是合法的 tar 归档：" + ex.getMessage());
        }
        requireNotEmpty(entries);
        return entries;
    }

    private static int readFully(InputStream in, byte[] buffer, int length) throws IOException {
        int offset = 0;
        while (offset < length) {
            int read = in.read(buffer, offset, length - offset);
            if (read < 0) {
                break;
            }
            offset += read;
        }
        return offset;
    }

    private static long parseOctal(byte[] header, int offset, int length) {
        String raw = new String(header, offset, length, StandardCharsets.US_ASCII)
                .replaceAll("\0.*$", "")
                .trim();
        if (raw.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(raw, 8);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static long accumulate(long total, long add, Limits limits) {
        long next = total + add;
        if (next > limits.maxTotalBytes()) {
            throw BizException.badRequest("归档解压后超过上限 " + (limits.maxTotalBytes() / 1024 / 1024) + "MB");
        }
        return next;
    }

    private static void requireNotEmpty(List<Entry> entries) {
        if (entries.isEmpty()) {
            throw BizException.badRequest("归档里没有任何文件");
        }
    }

    /** 读单个条目，超限直接报错（不静默截断，否则装进来的技能是残的）。 */
    private static byte[] readLimited(InputStream in, long maxFileBytes, String path) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long count = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            count += read;
            if (count > maxFileBytes) {
                throw BizException.badRequest("文件超过上限 " + (maxFileBytes / 1024) + "KB：" + path);
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /**
     * 包内再套包：仓库里分发 {@code dist/foo.zip} / {@code foo.skill} 时解开一层，
     * 让里面的 SKILL.md 也能被识别（前缀用归档名，避免与仓库里的同名文件撞路径）。
     */
    private static List<Entry> unpackNested(List<Entry> entries, Limits limits) {
        List<Entry> expanded = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            if (!isNestedArchive(entry.content())) {
                expanded.add(entry);
                continue;
            }
            List<Entry> inner;
            try {
                inner = unpack(entry.content(), limits);
            } catch (RuntimeException ex) {
                // 包里的 .zip 可能只是普通二进制附件：解不开就按普通文件处理，不因此整体失败
                expanded.add(entry);
                continue;
            }
            if (inner.stream().noneMatch(e -> isSkillMarkdown(e.path()))) {
                expanded.add(entry);
                continue;
            }
            // 内层归档自己也可能有一个顶层目录（zip 的 foo/、foo-1.0/），先剥掉再拼前缀
            List<Entry> innerStripped = stripSingleRoot(inner);
            String stem = stripArchiveSuffix(entry.path());
            for (Entry nested : innerStripped) {
                String path = normalize(stem + "/" + nested.path());
                if (path != null) {
                    expanded.add(new Entry(path, nested.content()));
                }
            }
        }
        return expanded;
    }

    private static boolean isNestedArchive(byte[] content) {
        if (content.length < 4) {
            return false;
        }
        if (!isZip(content) && !isGzip(content) && !isTar(content)) {
            return false;
        }
        // 只解「看起来像归档」的：技能正文里出现的 PK 头几乎不可能，但也别对每个文件都解压
        return true;
    }

    private static String stripArchiveSuffix(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        for (String suffix : new String[] {".tar.gz", ".tgz", ".zip", ".skill", ".tar"}) {
            if (lower.endsWith(suffix)) {
                return path.substring(0, path.length() - suffix.length());
            }
        }
        return path;
    }

    /** 统一成 {@code a/b/c} 形式；绝对路径与含 {@code ..} 的路径一律丢弃（zip-slip）。 */
    private static String normalize(String raw) {
        String path = raw.replace('\\', '/');
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        if (path.startsWith("/")) {
            return null;
        }
        for (String segment : path.split("/")) {
            if (segment.equals("..")) {
                return null;
            }
        }
        return path;
    }

    private static boolean isJunk(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.startsWith("__macosx/")
                || lower.startsWith(".git/")
                || lower.endsWith(".ds_store")
                || lower.equals(".gitignore")
                || lower.equals(".gitattributes");
    }

    /** GitHub archive 顶层是 {@code repo-branch/}：只有唯一顶层目录时整体剥掉，技能根目录才对得上。 */
    private static List<Entry> stripSingleRoot(List<Entry> entries) {
        String root = null;
        for (Entry entry : entries) {
            int slash = entry.path().indexOf('/');
            if (slash < 0) {
                return entries; // 有文件直接在顶层 → 不剥
            }
            String top = entry.path().substring(0, slash);
            if (root == null) {
                root = top;
            } else if (!root.equals(top)) {
                return entries; // 多个顶层目录 → 不剥
            }
        }
        List<Entry> stripped = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            stripped.add(new Entry(entry.path().substring(root.length() + 1), entry.content()));
        }
        return stripped;
    }

    private static String normalizeSubPath(String subPath) {
        if (subPath == null || subPath.isBlank()) {
            return "";
        }
        String path = subPath.replace('\\', '/').trim();
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        // 清单里写的是 "./skills/pdf"，本地路径要成 "skills/pdf"
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /** 只保留子目录下的条目（{@code skills/pdf} 及其子路径）。 */
    private static List<Entry> filterBySubPath(List<Entry> entries, String prefix) {
        List<Entry> filtered = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.path().equals(prefix) || entry.path().startsWith(prefix + "/")) {
                filtered.add(entry);
            }
        }
        if (filtered.isEmpty()) {
            throw BizException.badRequest("链接指向的目录里没有文件：" + prefix);
        }
        return filtered;
    }

    /**
     * 读技能包的插件清单 {@code .claude-plugin/marketplace.json}：{@code plugins[].skills} 里的每个目录
     * 归到该插件名下。清单缺失或格式不认识时返回空表（不影响安装，只是没有包名）。
     */
    private static Map<String, String> readPackManifest(List<Entry> entries) {
        Map<String, String> byRoot = new LinkedHashMap<>();
        for (Entry entry : entries) {
            String path = entry.path();
            if (!path.equals(".claude-plugin/marketplace.json") && !path.endsWith("/.claude-plugin/marketplace.json")) {
                continue;
            }
            try {
                JsonNode root = MAPPER.readTree(new String(entry.content(), StandardCharsets.UTF_8));
                JsonNode plugins = root.get("plugins");
                if (plugins == null || !plugins.isArray()) {
                    continue;
                }
                String base = path.equals(".claude-plugin/marketplace.json")
                        ? ""
                        : path.substring(0, path.length() - "/.claude-plugin/marketplace.json".length());
                for (JsonNode plugin : plugins) {
                    String name = plugin.path("name").asText("");
                    JsonNode skills = plugin.get("skills");
                    if (name.isEmpty() || skills == null || !skills.isArray()) {
                        continue;
                    }
                    for (JsonNode skill : skills) {
                        String dir = normalizeSubPath(skill.asText(""));
                        if (dir.isEmpty()) {
                            continue;
                        }
                        String rootPath = base.isEmpty() ? dir : base + "/" + dir;
                        byRoot.put(normalizeSubPath(rootPath), name);
                    }
                }
            } catch (IOException ex) {
                // 清单坏了不影响装技能：只是没有包名
                return Map.of();
            }
        }
        return byRoot;
    }

    private static List<Bundle> toBundles(List<Entry> entries, Limits limits, Map<String, String> packs) {
        // 先定位所有技能根目录（SKILL.md 的父目录，可能是空串=归档顶层）
        List<String> roots = new ArrayList<>();
        for (Entry entry : entries) {
            if (isSkillMarkdown(entry.path())) {
                String dir = parentOf(entry.path());
                if (!roots.contains(dir)) {
                    roots.add(dir);
                }
            }
        }
        roots.sort(String::compareTo);
        if (roots.isEmpty()) {
            throw BizException.badRequest("归档里没有找到 SKILL.md（标准 Skill 必须有 SKILL.md）");
        }

        List<Bundle> bundles = new ArrayList<>();
        for (String root : roots) {
            String markdown = null;
            Map<String, String> resources = new LinkedHashMap<>();
            for (Entry entry : entries) {
                if (!underRoot(entry.path(), root)) {
                    continue;
                }
                if (isSkillMarkdown(entry.path())) {
                    if (parentOf(entry.path()).equals(root)) {
                        markdown = decodeText(entry.content());
                    }
                    continue; // 更深层的 SKILL.md 属于其它技能，不当资源
                }
                if (belongsToNestedSkill(entry.path(), root, roots)) {
                    continue;
                }
                String relative = root.isEmpty() ? entry.path() : entry.path().substring(root.length() + 1);
                if (relative.length() > 500) {
                    throw BizException.badRequest("资源路径超过 500 字符：" + relative);
                }
                resources.put(relative, encode(entry.content()));
                if (resources.size() > limits.maxResourcesPerSkill()) {
                    throw BizException.badRequest(
                            "技能 " + (root.isEmpty() ? "(根目录)" : root) + " 的资源超过 "
                                    + limits.maxResourcesPerSkill() + " 个");
                }
            }
            if (markdown == null) {
                continue;
            }
            bundles.add(new Bundle(root, packs.get(root), markdown, resources));
        }
        if (bundles.isEmpty()) {
            throw BizException.badRequest("归档里没有找到可用的 SKILL.md");
        }
        return bundles;
    }

    private static boolean isSkillMarkdown(String path) {
        return path.equalsIgnoreCase("SKILL.md") || path.toLowerCase(Locale.ROOT).endsWith("/skill.md");
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static boolean underRoot(String path, String root) {
        return root.isEmpty() || path.equals(root) || path.startsWith(root + "/");
    }

    /** 该文件是否落在**另一个更深**的技能根目录下（内层技能的资源不能算到外层头上）。 */
    private static boolean belongsToNestedSkill(String path, String root, List<String> roots) {
        for (String other : roots) {
            if (other.equals(root) || other.isEmpty()) {
                continue;
            }
            if (other.length() > root.length()
                    && other.startsWith(root.isEmpty() ? "" : root + "/")
                    && underRoot(path, other)) {
                return true;
            }
        }
        return false;
    }

    /** 能按 UTF-8 正常解码就当文本，否则按官方约定存 {@code base64:} + Base64。 */
    private static String encode(byte[] content) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException ex) {
            return "base64:" + Base64.getEncoder().encodeToString(content);
        }
    }

    private static String decodeText(byte[] content) {
        // SKILL.md 必须是文本：解不出来就是仓库本身有问题，直接报错而不是塞进模型上下文
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException ex) {
            throw BizException.badRequest("SKILL.md 不是合法的 UTF-8 文本");
        }
    }
}
