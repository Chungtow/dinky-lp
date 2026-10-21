/*
 *
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.dinky.resource.semantic;

import org.dinky.data.enums.Status;
import org.dinky.data.exception.BusException;
import org.dinky.data.model.SystemConfiguration;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Options;
import org.apache.hadoop.fs.Path;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.lang.Opt;
import cn.hutool.core.util.StrUtil;

/**
 * 语义根（默认 {@code /semantic}）HDFS 客户端 —— 与 JupyterLab 侧共用同一套目录约定。
 *
 * <p><b>为什么不复用 {@code HdfsResourceManager}</b>：它的每个方法都先经
 * {@code getFilePath(path)} 拼上 {@code resourcesUploadBasePath}（生产 {@code /dinky}、
 * 测试槽 {@code /dinky-lp}），语义根不在该前缀下，复用会被拼成 {@code /dinky/semantic}。
 * 这里只借用它读的同一份 HDFS 连接配置（core-site / hdfs-site / user / defaultFS），
 * 自行管理 {@link FileSystem} 实例。
 *
 * <p><b>守卫（对应实施计划 §4.1 C1~C5）</b>：
 * <ul>
 *   <li>C1 路径必须落在语义根内；禁止 {@code ..} 穿越、绝对路径、反斜杠；{@code _published}
 *       与 {@code meta.json}、{@code target/} 一律只读</li>
 *   <li>C2 单文件 ≤ 1MB，单项目 ≤ 300 个文件（超限直接报错，不做静默截断）</li>
 *   <li>C3 写入走 {@code <path>.tmp-<uuid>} → {@code rename(OVERWRITE)}，避免半截文件</li>
 *   <li>C4 单例 + 配置指纹：设置页改了 HDFS 配置后自动重建连接，无需重启</li>
 *   <li>C5 错误统一映射到可读 {@link Status}（关闭 / 路径非法 / 不存在 / 过大 / 冲突 / IO 失败）</li>
 * </ul>
 *
 * <p><b>注意</b>：{@link FileSystem#get} 返回的是 Hadoop 进程级缓存实例（按 URI+用户），
 * 与资源中心共用同一连接，因此本类**从不 close**；配置变化时只丢弃引用重建。
 */
public class SemanticHdfsClient {

    /** 单文件大小上限：1MB（语义文件是 YAML/SQL 文本，远超此值说明走错了路径） */
    public static final long MAX_FILE_BYTES = 1024L * 1024L;

    /** 单项目文件数上限（含 target/ 之外的源文件；超过即认为不是语义项目） */
    public static final int MAX_PROJECT_FILES = 300;

    /** 目录递归深度上限（语义项目实际只有 2~3 层） */
    private static final int MAX_DEPTH = 5;

    /** 项目名：字母数字下划线开头，允许 . - _，最长 64（与 dbt 项目名约束一致） */
    private static final Pattern PROJECT_NAME = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}$");

    /** 只读文件名（项目根） */
    private static final String META_FILE = "meta.json";

    /** 只读目录（相对项目根） */
    private static final String TARGET_DIR = "target/";

    /** 历史快照目录（相对项目根）：不下钻、不入列、不可写，语义同 target/ */
    private static final String VERSIONS_DIR = "_versions/";

    /** 回收站目录（相对项目根）：P2.5 软删落点，不下钻、不入列、不可写 */
    private static final String TRASH_DIR = ".trash/";

    /** 目录名（walk 剪枝按名字判定，见 {@link #isInternalDir}） */
    private static final String TARGET_NAME = "target";

    private static final String VERSIONS_NAME = "_versions";

    /** 发布区（语义根下一级）：只在 ensureWritable 里做越权拦截 */
    private static final String PUBLISHED_DIR = "_published/";

    /** 回收站保留天数（仅用于提示文案，purge 由父仓库脚本执行） */
    public static final int TRASH_KEEP_DAYS = 7;

    private static final SemanticHdfsClient INSTANCE = new SemanticHdfsClient();

    private static final SystemConfiguration CONF = SystemConfiguration.getInstances();

    private volatile FileSystem fs;

    private volatile String fsKey;

    private volatile Configuration fsConf;

    /** 仅供 rename(OVERWRITE) 使用：FileSystem 的同名重载是 protected，FileContext 才有公开变体 */
    private volatile FileContext fileContext;

    private volatile String fcKey;

    private SemanticHdfsClient() {}

    public static SemanticHdfsClient getInstance() {
        return INSTANCE;
    }

    /** 功能开关（默认 false） */
    public boolean enabled() {
        return Boolean.TRUE.equals(CONF.getSemanticEnable().getValue());
    }

    /** 语义根，规范化：以 / 开头、无结尾 /（如 {@code /semantic}） */
    public String basePath() {
        String raw = CONF.getSemanticHdfsBasePath().getValue();
        String path = StrUtil.isBlank(raw) ? "/semantic" : raw.trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    // ------------------------------------------------------------------ 读

    /** 列出语义根下的一级项目（跳过 {@code _published}、{@code _templates} 等下划线目录） */
    public List<String> listProjects() {
        requireEnabled();
        List<String> names = new ArrayList<>();
        Path base = new Path(basePath());
        try {
            for (FileStatus status : fs().listStatus(base)) {
                String name = status.getPath().getName();
                if (!status.isDirectory() || name.startsWith("_") || name.startsWith(".")) {
                    continue;
                }
                if (PROJECT_NAME.matcher(name).matches()) {
                    names.add(name);
                }
            }
            Collections.sort(names);
            return names;
        } catch (FileNotFoundException e) {
            // 语义根尚不存在（未初始化）→ 空列表，不算错误
            return names;
        } catch (IOException e) {
            throw new BusException(e, Status.SEMANTIC_READ_FAILED);
        }
    }

    /** 项目目录最近修改时间（epoch ms）；目录不存在返回 0 */
    public long projectLastModified(String project) {
        requireEnabled();
        try {
            return fs().getFileStatus(new Path(projectDir(project))).getModificationTime();
        } catch (FileNotFoundException e) {
            return 0L;
        } catch (IOException e) {
            throw new BusException(e, Status.SEMANTIC_READ_FAILED);
        }
    }

    /** 递归列出项目内条目（相对路径），超限直接报错 */
    public List<SemanticFileEntry> listFiles(String project) {
        requireEnabled();
        List<SemanticFileEntry> entries = new ArrayList<>();
        walk(new Path(projectDir(project)), "", entries, 0);
        return entries;
    }

    private void walk(Path dir, String prefix, List<SemanticFileEntry> entries, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        FileStatus[] children;
        try {
            children = fs().listStatus(dir);
        } catch (FileNotFoundException e) {
            return;
        } catch (IOException e) {
            throw new BusException(e, Status.SEMANTIC_READ_FAILED);
        }
        for (FileStatus status : children) {
            String name = status.getPath().getName();
            // 内部目录（dbt 产物 / 历史快照 / 回收站 / 隐藏目录）：不入列也不下钻。
            // 不下钻：target 体积大；_versions 与 .trash 属内部数据且会撑爆 300 文件上限。
            // 不入列：.trash 是 P2.5 软删落点，出现在文件树里会误导用户去删回收站。
            if (status.isDirectory() && isInternalDir(name)) {
                continue;
            }
            String rel = prefix.isEmpty() ? name : prefix + "/" + name;
            boolean readOnly = isReadOnly(rel);
            if (entries.size() >= MAX_PROJECT_FILES) {
                throw new BusException(Status.SEMANTIC_TOO_MANY_FILES);
            }
            entries.add(new SemanticFileEntry(
                    rel, status.isDirectory(), status.getLen(), status.getModificationTime(), readOnly));
            if (status.isDirectory()) {
                walk(status.getPath(), rel, entries, depth + 1);
            }
        }
    }

    /** 内部目录判定：dbt 产物 / 历史快照 / 回收站，以及一切隐藏目录（`.` 开头） */
    private boolean isInternalDir(String name) {
        return name.startsWith(".")
                || TARGET_NAME.equals(name)
                || VERSIONS_NAME.equals(name)
                || "logs".equals(name)
                || "dbt_packages".equals(name);
    }

    /** 读文件内容（≤1MB），同时返回 mtime 供前端做乐观锁 */
    public SemanticFile read(String project, String relPath) {
        requireEnabled();
        String clean = normalizeRel(relPath);
        Path path = new Path(absoluteOf(project, clean));
        try {
            FileStatus status = fs().getFileStatus(path);
            if (status.isDirectory()) {
                throw new BusException(Status.SEMANTIC_PATH_ILLEGAL);
            }
            if (status.getLen() > MAX_FILE_BYTES) {
                throw new BusException(Status.SEMANTIC_FILE_TOO_LARGE);
            }
            try (FSDataInputStream in = fs().open(path)) {
                byte[] bytes = IoUtil.readBytes(in, (int) status.getLen());
                return new SemanticFile(
                        clean,
                        new String(bytes, StandardCharsets.UTF_8),
                        status.getLen(),
                        status.getModificationTime());
            }
        } catch (FileNotFoundException e) {
            throw new BusException(Status.SEMANTIC_FILE_NOT_FOUND);
        } catch (IOException e) {
            throw new BusException(e, Status.SEMANTIC_READ_FAILED);
        }
    }

    // ------------------------------------------------------------------ 写

    /**
     * 原子写 + mtime 乐观锁。
     *
     * @param baseMtime 前端读文件时拿到的 HDFS mtime；>0 时若 HDFS 现状 mtime 与之不符则判定冲突（409 语义）
     * @return 写入后的新 mtime
     */
    public long write(String project, String relPath, String content, Long baseMtime) {
        requireEnabled();
        String clean = normalizeRel(relPath);
        ensureWritable(clean);
        byte[] bytes = StrUtil.nullToEmpty(content).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new BusException(Status.SEMANTIC_FILE_TOO_LARGE);
        }
        String abs = absoluteOf(project, clean);
        Path target = new Path(abs);
        try {
            FileSystem fileSystem = fs();
            boolean exists = fileSystem.exists(target);
            if (exists) {
                FileStatus current = fileSystem.getFileStatus(target);
                if (current.isDirectory()) {
                    throw new BusException(Status.SEMANTIC_PATH_ILLEGAL);
                }
                if (baseMtime != null && baseMtime > 0 && current.getModificationTime() != baseMtime.longValue()) {
                    throw new BusException(Status.SEMANTIC_WRITE_CONFLICT);
                }
            } else if (baseMtime != null && baseMtime > 0) {
                // 前端认为文件存在（带回 baseMtime），HDFS 上却没有 → 被别人删了，同样按冲突处理
                throw new BusException(Status.SEMANTIC_WRITE_CONFLICT);
            }
            Path tmp = new Path(abs + ".tmp-" + UUID.randomUUID().toString().substring(0, 8));
            try (FSDataOutputStream out = fileSystem.create(tmp, true)) {
                out.write(bytes);
                out.hflush();
            }
            // OVERWRITE：单次 NameNode 内的原子替换（FileSystem.rename 的 OVERWRITE 重载是 protected，
            // FileContext 才有公开变体）。HDFS 的 rename 对已存在目标默认报 File exists，必须指定 OVERWRITE。
            try {
                fileContext().rename(tmp, target, Options.Rename.OVERWRITE);
            } catch (Exception e) {
                fileSystem.delete(tmp, false);
                throw new BusException(e, Status.SEMANTIC_WRITE_FAILED);
            }
            return fileSystem.getFileStatus(target).getModificationTime();
        } catch (IOException e) {
            throw new BusException(e, Status.SEMANTIC_WRITE_FAILED);
        }
    }

    // ------------------------------------------------------------------ 文件 CRUD（P2.5）

    /**
     * 新建目录（父目录必须已存在）。
     *
     * <p>刻意不用 {@code fs.mkdirs} 的"自动建父目录"语义：语义项目里目录层级本身就是信息，
     * 拼错父目录名时静默生成整条链路比直接报错更难排查。
     */
    public void mkdirs(String project, String rel) {
        requireEnabled();
        String clean = normalizeRel(rel);
        ensureWritable(clean);
        Path target = new Path(absoluteOf(project, clean));
        try {
            FileSystem fileSystem = fs();
            if (fileSystem.exists(target)) {
                throw new BusException(Status.SEMANTIC_TARGET_EXISTS);
            }
            if (!isExistingDir(fileSystem, target.getParent())) {
                throw new BusException(Status.SEMANTIC_PARENT_NOT_FOUND);
            }
            if (!fileSystem.mkdirs(target)) {
                throw new BusException(Status.SEMANTIC_WRITE_FAILED);
            }
        } catch (IOException e) {
            throw new BusException(e, Status.SEMANTIC_WRITE_FAILED);
        }
    }

    /**
     * 软删：把条目移入项目内回收站 {@code .trash/<yyyyMMddHHmmss>-<uuid8>/<rel>}。
     *
     * <p>用 {@code rename} 而不是 {@code delete}：单次 NameNode 元数据操作、同项目内原子，
     * 误删可秒级还原（{@code hdfs dfs -mv} 搬回原位）。真删由父仓库清理脚本按保留期执行，
     * Dinky 不提供"永久删除"。
     *
     * @param baseMtime 仅对**文件**生效：目录 mtime 只随子项增删变化，语义不稳定，参与乐观锁会频繁误报
     * @return 回收站内的相对路径（供前端提示与人工恢复）
     */
    public String delete(String project, String rel, boolean recursive, Long baseMtime) {
        requireEnabled();
        String clean = normalizeRel(rel);
        ensureWritable(clean);
        Path source = new Path(absoluteOf(project, clean));
        try {
            FileSystem fileSystem = fs();
            FileStatus status;
            try {
                status = fileSystem.getFileStatus(source);
            } catch (FileNotFoundException e) {
                throw new BusException(Status.SEMANTIC_FILE_NOT_FOUND);
            }
            if (status.isDirectory()) {
                if (!recursive && fileSystem.listStatus(source).length > 0) {
                    throw new BusException(Status.SEMANTIC_NOT_EMPTY);
                }
            } else if (baseMtime != null && baseMtime > 0 && status.getModificationTime() != baseMtime.longValue()) {
                throw new BusException(Status.SEMANTIC_WRITE_CONFLICT);
            }
            String trashRel = TRASH_DIR + trashStamp() + "/" + clean;
            Path trashTarget = new Path(absoluteOf(project, trashRel));
            Path trashDir = trashTarget.getParent();
            if (!fileSystem.mkdirs(trashDir) && !fileSystem.exists(trashDir)) {
                throw new BusException(Status.SEMANTIC_WRITE_FAILED);
            }
            // 回收站目录名含时间戳 + uuid，目标不可能已存在，因此不需要 OVERWRITE
            if (!fileSystem.rename(source, trashTarget)) {
                throw new BusException(Status.SEMANTIC_WRITE_FAILED);
            }
            return trashRel;
        } catch (IOException e) {
            throw new BusException(e, Status.SEMANTIC_WRITE_FAILED);
        }
    }

    /**
     * 项目内改名 / 移动（目标必须不存在）。
     *
     * <p>用 {@link FileSystem#rename} 而不是 {@link #write} 里的
     * {@code FileContext.rename(OVERWRITE)}：前者目标存在时返回 false（据此报
     * {@code SEMANTIC_TARGET_EXISTS}），后者是"原子替换"语义，会静默覆盖目标内容。
     */
    public void renameInProject(String project, String from, String to, Long baseMtime) {
        requireEnabled();
        String srcClean = normalizeRel(from);
        String dstClean = normalizeRel(to);
        ensureWritable(srcClean);
        ensureWritable(dstClean);
        if (srcClean.equals(dstClean)) {
            return;
        }
        Path source = new Path(absoluteOf(project, srcClean));
        Path target = new Path(absoluteOf(project, dstClean));
        try {
            FileSystem fileSystem = fs();
            FileStatus sourceStatus;
            try {
                sourceStatus = fileSystem.getFileStatus(source);
            } catch (FileNotFoundException e) {
                throw new BusException(Status.SEMANTIC_FILE_NOT_FOUND);
            }
            if (sourceStatus.isDirectory()) {
                if (dstClean.startsWith(srcClean + "/")) {
                    throw new BusException(Status.SEMANTIC_MOVE_INTO_SELF);
                }
            } else if (baseMtime != null
                    && baseMtime > 0
                    && sourceStatus.getModificationTime() != baseMtime.longValue()) {
                // 与 write/delete 同判据：文件被别人改过就不让改名
                throw new BusException(Status.SEMANTIC_WRITE_CONFLICT);
            }
            if (fileSystem.exists(target)) {
                throw new BusException(Status.SEMANTIC_TARGET_EXISTS);
            }
            if (!isExistingDir(fileSystem, target.getParent())) {
                throw new BusException(Status.SEMANTIC_PARENT_NOT_FOUND);
            }
            if (!fileSystem.rename(source, target)) {
                throw new BusException(Status.SEMANTIC_WRITE_FAILED);
            }
        } catch (IOException e) {
            throw new BusException(e, Status.SEMANTIC_WRITE_FAILED);
        }
    }

    /** 单条目状态（mtime / size / 是否目录），供乐观锁与"目录非空"判断 */
    public FileStatus statOf(String project, String rel) {
        requireEnabled();
        String clean = normalizeRel(rel);
        try {
            return fs().getFileStatus(new Path(absoluteOf(project, clean)));
        } catch (FileNotFoundException e) {
            throw new BusException(Status.SEMANTIC_FILE_NOT_FOUND);
        } catch (IOException e) {
            throw new BusException(e, Status.SEMANTIC_READ_FAILED);
        }
    }

    /** 父目录存在且是目录（新建/改名共用） */
    private boolean isExistingDir(FileSystem fileSystem, Path path) throws IOException {
        return path != null
                && fileSystem.exists(path)
                && fileSystem.getFileStatus(path).isDirectory();
    }

    /** 回收站子目录名：{@code yyyyMMddHHmmss-<uuid8>}，字典序即时间序，便于清理脚本按天裁剪 */
    private String trashStamp() {
        return new SimpleDateFormat("yyyyMMddHHmmss").format(new Date())
                + "-"
                + UUID.randomUUID().toString().substring(0, 8);
    }

    // ------------------------------------------------------------------ 守卫 / 工具

    /** 项目名校验 */
    public String normalizeProject(String project) {
        String name = StrUtil.trimToEmpty(project);
        if (name.startsWith("_")
                || name.startsWith(".")
                || !PROJECT_NAME.matcher(name).matches()) {
            throw new BusException(Status.SEMANTIC_PATH_ILLEGAL);
        }
        return name;
    }

    /** 相对路径校验：拒绝对路径、{@code ..}、空段、超长 */
    public String normalizeRel(String relPath) {
        String rel = StrUtil.trimToEmpty(relPath).replace('\\', '/');
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        if (rel.isEmpty()) {
            throw new BusException(Status.SEMANTIC_PATH_ILLEGAL);
        }
        if (rel.length() > 512) {
            throw new BusException(Status.SEMANTIC_PATH_ILLEGAL);
        }
        for (String segment : rel.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment) || segment.length() > 128) {
                throw new BusException(Status.SEMANTIC_PATH_ILLEGAL);
            }
        }
        return rel;
    }

    /** 只读判定：meta.json 与 target/、_versions/、.trash/ 下的一切 */
    public boolean isReadOnly(String rel) {
        return META_FILE.equals(rel)
                || rel.startsWith(TARGET_DIR)
                || rel.startsWith(VERSIONS_DIR)
                || rel.startsWith(TRASH_DIR);
    }

    /**
     * 写 / 改前守卫：只读项、发布区、导出目录一律拒绝。
     *
     * <p>P2.5 起 {@code rename / delete / move} 的**源路径**也要过这里——只守"写"不守"删"
     * 等于让受保护项换个方式被销毁。
     */
    private void ensureWritable(String rel) {
        String lower = rel.toLowerCase();
        if (isReadOnly(rel) || lower.startsWith(PUBLISHED_DIR) || lower.startsWith(".git/")) {
            throw new BusException(Status.SEMANTIC_PATH_ILLEGAL);
        }
    }

    private String projectDir(String project) {
        return basePath() + "/" + normalizeProject(project);
    }

    private String absoluteOf(String project, String rel) {
        return projectDir(project) + "/" + rel;
    }

    private void requireEnabled() {
        if (!enabled()) {
            throw new BusException(Status.SEMANTIC_DISABLED);
        }
    }

    /** 按配置指纹复用 FileSystem；配置变更后自动重建（不 close，见类注释） */
    private FileSystem fs() {
        String key = StrUtil.join(
                "|",
                StrUtil.nullToEmpty(CONF.getResourcesHdfsDefaultFS().getValue()),
                StrUtil.nullToEmpty(CONF.getResourcesHdfsUser().getValue()),
                Integer.toHexString(
                        StrUtil.nullToEmpty(CONF.getResourcesHdfsCoreSite().getValue())
                                .hashCode()),
                Integer.toHexString(
                        StrUtil.nullToEmpty(CONF.getResourcesHdfsHdfsSite().getValue())
                                .hashCode()));
        FileSystem current = fs;
        if (current != null && key.equals(fsKey)) {
            return current;
        }
        synchronized (this) {
            if (fs != null && key.equals(fsKey)) {
                return fs;
            }
            Configuration configuration = new Configuration();
            Charset charset = StandardCharsets.UTF_8;
            String coreSite = CONF.getResourcesHdfsCoreSite().getValue();
            String hdfsSite = CONF.getResourcesHdfsHdfsSite().getValue();
            Opt.ofBlankAble(coreSite).ifPresent(x -> configuration.addResource(IoUtil.toStream(x, charset)));
            Opt.ofBlankAble(hdfsSite).ifPresent(x -> configuration.addResource(IoUtil.toStream(x, charset)));
            configuration.reloadConfiguration();
            if (StrUtil.isEmpty(coreSite)) {
                configuration.set(
                        "fs.defaultFS", CONF.getResourcesHdfsDefaultFS().getValue());
            }
            try {
                fs = FileSystem.get(
                        FileSystem.getDefaultUri(configuration),
                        configuration,
                        CONF.getResourcesHdfsUser().getValue());
            } catch (Exception e) {
                throw new BusException(e, Status.SEMANTIC_READ_FAILED);
            }
            fsKey = key;
            fsConf = configuration;
            return fs;
        }
    }

    /** 与 {@link #fs()} 同指纹复用；仅用于带 OVERWRITE 的原子 rename */
    private FileContext fileContext() {
        String key = fsKey;
        FileContext current = fileContext;
        if (current != null && key != null && key.equals(fcKey)) {
            return current;
        }
        synchronized (this) {
            if (fileContext != null && key != null && key.equals(fcKey)) {
                return fileContext;
            }
            try {
                fileContext = FileContext.getFileContext(FileSystem.getDefaultUri(fsConf), fsConf);
            } catch (Exception e) {
                throw new BusException(e, Status.SEMANTIC_WRITE_FAILED);
            }
            fcKey = key;
            return fileContext;
        }
    }

    /** 读文件结果（内容 + 元数据） */
    public static class SemanticFile {

        private final String path;
        private final String content;
        private final long size;
        private final long modifiedAt;

        public SemanticFile(String path, String content, long size, long modifiedAt) {
            this.path = path;
            this.content = content;
            this.size = size;
            this.modifiedAt = modifiedAt;
        }

        public String getPath() {
            return path;
        }

        public String getContent() {
            return content;
        }

        public long getSize() {
            return size;
        }

        public long getModifiedAt() {
            return modifiedAt;
        }
    }
}
