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

package org.dinky.ai.skill;

import org.dinky.ai.asset.AssetType;
import org.dinky.ai.mention.SourceRefResolver;
import org.dinky.data.exception.BusException;
import org.dinky.data.model.Column;
import org.dinky.data.model.DataBase;
import org.dinky.data.model.KbCollection;
import org.dinky.data.model.Schema;
import org.dinky.data.model.Skill;
import org.dinky.data.model.Table;
import org.dinky.data.vo.SemanticPublishedAssetVO;
import org.dinky.data.vo.SemanticPublishedProjectVO;
import org.dinky.data.vo.SkillHandleValidationVO;
import org.dinky.metadata.driver.Driver;
import org.dinky.service.DataBaseService;
import org.dinky.service.KbCollectionService;
import org.dinky.service.SemanticFileService;
import org.dinky.service.SkillService;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Skill 正文 {@code @} 句柄校验（保存后校验 / E4）。
 *
 * <p><b>为什么是独立类而不是塞进 Service</b>：它只做「读文本 → 逐句柄判状态」，不写任何数据、
 * 不参与保存事务；放进 {@code SkillServiceImpl} 会把"存"与"查"耦合在一起，
 * 而校验还要用到数据源元数据（{@code DataBaseService} + {@code Driver}）这类与 skill 无关的依赖。
 *
 * <p><b>双语法（explicit / bare）</b>：反引号包裹的 {@code `@type/code`} 是规范写法（严格校验），
 * 裸写 {@code @type/code} 为历史兼容（同样校验，但<b>只给 info 级提示</b>——否则存量 skill
 * 一打开就是满屏黄色波浪线）。判定权在后端，前端只按 {@code syntax} 决定渲染强度。
 *
 * <p><b>幂等与成本</b>：一次请求内对同一数据源的库/表清单各查一次（{@link Ctx} 缓存），
 * 句柄通常 &lt; 20 个；数据源级引用（{@code @source/<ds>}）<b>不建连</b>，只有下钻到库/表/字段才连元数据。
 *
 * @since 2026/10/30
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillHandleValidator {

    /** 规范写法：反引号包裹 */
    public static final String SYNTAX_EXPLICIT = "explicit";

    /** 历史兼容：裸写 */
    public static final String SYNTAX_BARE = "bare";

    public static final String STATUS_OK = "ok";

    public static final String STATUS_NOT_FOUND = "not_found";

    public static final String STATUS_DENIED = "denied";

    public static final String STATUS_UNKNOWN_TYPE = "unknown_type";

    /** 围栏代码块（``` ... ```）：里面的 {@code @} 是示例，不参与校验 */
    private static final Pattern FENCED_BLOCK = Pattern.compile("(?s)```.*?```");

    /**
     * 句柄匹配：<b>explicit 分支在前</b>，同一位置只命中一次（反引号形式优先）。
     *
     * <p>各段字符集与 {@link SourceRefResolver} 同源（字母/数字/下划线/连字符/点/斜杠/中文），
     * 因此路径式句柄能整段被捕获，而 {@code @} 后面紧跟空白的场景自然不命中。
     */
    private static final Pattern HANDLE =
            Pattern.compile("`@([A-Za-z]+)/([^`\\r\\n]+)`" + "|@([A-Za-z]+)/([A-Za-z0-9_.$\\-\\u4e00-\\u9fa5/]+)");

    private final SkillService skillService;

    private final KbCollectionService kbCollectionService;

    private final SemanticFileService semanticFileService;

    private final DataBaseService dataBaseService;

    /**
     * 校验一份正文里的全部句柄。
     *
     * @param content SKILL.md 全文
     * @return 逐句柄结果 + 汇总（永不为 {@code null}）
     * @throws BusException 正文超过 256KB（与文件写接口同口径）
     */
    public SkillHandleValidationVO validate(String content) {
        SkillHandleValidationVO vo = new SkillHandleValidationVO();
        if (StrUtil.isEmpty(content)) {
            return vo;
        }
        if (content.length() > SkillDocParser.MAX_DOC_SIZE) {
            throw new BusException("正文过大（最多 " + SkillDocParser.MAX_DOC_SIZE + " 字节），请拆分文件后再校验");
        }
        Ctx ctx = new Ctx();
        String masked = maskFencedBlocks(content);
        Matcher matcher = HANDLE.matcher(masked);
        Set<Integer> seen = new LinkedHashSet<>();
        while (matcher.find()) {
            boolean explicit = matcher.group(1) != null;
            String type = explicit ? matcher.group(1) : matcher.group(3);
            String code = explicit ? matcher.group(2) : matcher.group(4);
            int atIndex = explicit ? matcher.start() + 1 : matcher.start();
            if (!seen.add(atIndex)) {
                continue;
            }
            SkillHandleValidationVO.Handle handle = buildHandle(content, matcher, type, code, explicit, atIndex);
            if (handle == null) {
                continue;
            }
            resolve(handle, ctx);
            vo.getHandles().add(handle);
            count(vo.getSummary(), handle);
        }
        return vo;
    }

    /** 组装一条句柄（含行列定位），类型不在白名单时直接给 {@code unknown_type}。 */
    private SkillHandleValidationVO.Handle buildHandle(
            String content, Matcher matcher, String type, String code, boolean explicit, int atIndex) {
        String trimmedCode = StrUtil.removeSuffix(StrUtil.trim(code), ".");
        if (StrUtil.isBlank(trimmedCode)) {
            return null;
        }
        SkillHandleValidationVO.Handle handle = new SkillHandleValidationVO.Handle();
        handle.setSyntax(explicit ? SYNTAX_EXPLICIT : SYNTAX_BARE);
        handle.setType(type.toLowerCase(Locale.ROOT));
        handle.setCode(trimmedCode);
        handle.setRaw(explicit ? "`@" + type + "/" + trimmedCode + "`" : "@" + type + "/" + trimmedCode);
        int line = 1;
        int column = 1;
        int limit = Math.min(atIndex, content.length());
        for (int i = 0; i < limit; i++) {
            if (content.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        handle.setLine(line);
        handle.setColumn(column);
        if (AssetType.of(type) == null) {
            handle.setStatus(STATUS_UNKNOWN_TYPE);
            handle.setMessage("未知的引用类型（支持：" + supportedTypes() + "）");
        }
        return handle;
    }

    /** 按类型解析存在性 / 可见性（{@code status} 已定的直接跳过）。 */
    private void resolve(SkillHandleValidationVO.Handle handle, Ctx ctx) {
        if (handle.getStatus() != null) {
            return;
        }
        AssetType type = AssetType.of(handle.getType());
        switch (type) {
            case SKILL:
            case DOC:
                resolveSkillOrDoc(handle, type);
                break;
            case KB:
                resolveKb(handle);
                break;
            case SEMANTIC:
                resolveSemantic(handle);
                break;
            case SOURCE:
                resolveSource(handle, ctx);
                break;
            case TABLE:
            case COLUMN:
                // 历史类型：编辑器已不再注册候选（计划 §3.6），校验不做存在性判定，只提示改写法
                markOk(handle, null, "该写法已不建议使用，请改用 @source/<数据源>/<库>/<表>");
                break;
            default:
                // topic / env：候选来自名册接口，校验端点不做存在性判定（避免误报为不存在）
                markOk(handle, null, null);
                break;
        }
    }

    private void resolveSkillOrDoc(SkillHandleValidationVO.Handle handle, AssetType type) {
        boolean wantDoc = type == AssetType.DOC;
        Skill visible = skillService.findVisibleByName(handle.getCode());
        if (visible != null && isDoc(visible) == wantDoc) {
            markOk(handle, visible.getName(), null);
            return;
        }
        Skill any = skillService.findAnyByName(handle.getCode());
        if (any != null && isDoc(any) == wantDoc) {
            handle.setStatus(STATUS_DENIED);
            handle.setMessage("无访问权限（该资产对当前用户不可见）");
            return;
        }
        handle.setStatus(STATUS_NOT_FOUND);
        handle.setMessage(wantDoc ? "知识文档不存在" : "技能不存在");
    }

    private void resolveKb(SkillHandleValidationVO.Handle handle) {
        try {
            KbCollection collection = kbCollectionService.getByCode(handle.getCode());
            if (collection == null) {
                handle.setStatus(STATUS_NOT_FOUND);
                handle.setMessage("知识库不存在");
                return;
            }
            if (!kbCollectionService.isVisible(handle.getCode())) {
                handle.setStatus(STATUS_DENIED);
                handle.setMessage("无访问权限（未被授予该知识库）");
                return;
            }
            markOk(handle, collection.getName(), null);
        } catch (Exception e) {
            log.warn("@kb 校验失败（code={}）: {}", handle.getCode(), e.getMessage());
            handle.setStatus(STATUS_OK);
            handle.setMessage("未能校验（知识库服务不可用）");
        }
    }

    private void resolveSemantic(SkillHandleValidationVO.Handle handle) {
        String project = StrUtil.subBefore(handle.getCode(), "/", false);
        String asset = StrUtil.subAfter(handle.getCode(), "/", false);
        try {
            SemanticPublishedProjectVO hit = null;
            for (SemanticPublishedProjectVO item : safeProjects()) {
                if (item != null && StrUtil.equals(item.getName(), project)) {
                    hit = item;
                    break;
                }
            }
            if (hit == null) {
                handle.setStatus(STATUS_NOT_FOUND);
                handle.setMessage("语义项目不存在或未发布");
                return;
            }
            if (StrUtil.isBlank(asset)) {
                markOk(handle, hit.getName(), null);
                return;
            }
            for (SemanticPublishedAssetVO item : safeAssets(project)) {
                if (item != null && StrUtil.equals(item.getName(), asset)) {
                    markOk(handle, item.getName(), null);
                    return;
                }
            }
            handle.setStatus(STATUS_NOT_FOUND);
            handle.setMessage("语义资产不存在（项目已发布，但该项目下没有这个表 / 指标）");
        } catch (Exception e) {
            log.warn("@semantic 校验失败（code={}）: {}", handle.getCode(), e.getMessage());
            handle.setStatus(STATUS_OK);
            handle.setMessage("未能校验（语义服务不可用）");
        }
    }

    private void resolveSource(SkillHandleValidationVO.Handle handle, Ctx ctx) {
        SourceRefResolver.Ref ref = SourceRefResolver.parse(handle.getCode());
        if (ref == null) {
            handle.setStatus(STATUS_NOT_FOUND);
            handle.setMessage("路径不合法：应为 @source/<数据源>[/<库>[/<表>[.<字段>]]]");
            return;
        }
        Map<String, DataBase> sources = ctx.sources(dataBaseService);
        DataBase dataBase = SourceRefResolver.resolve(sources, ref.getDatasource());
        if (dataBase == null) {
            handle.setStatus(STATUS_NOT_FOUND);
            handle.setMessage("本租户下没有启用中的同名数据源" + SourceRefResolver.availableNames(sources));
            return;
        }
        if (ref.level() == 1) {
            markOk(handle, dataBase.getName(), null);
            return;
        }
        try (Driver driver = Driver.build(dataBase.getDriverConfig())) {
            switch (ref.level()) {
                case 2:
                    checkDatabase(handle, driver, ctx, dataBase, ref);
                    break;
                case 3:
                    checkTable(handle, driver, ctx, dataBase, ref);
                    break;
                default:
                    checkColumn(handle, driver, ctx, dataBase, ref);
                    break;
            }
        } catch (Exception e) {
            // 连接失败 ≠ 资源不存在：不报警告，只说明未能校验（避免把网络问题渲染成"资源不存在"）
            log.warn("@source 校验失败（path={}）: {}", handle.getCode(), e.getMessage());
            handle.setStatus(STATUS_OK);
            handle.setMessage("未能校验（数据源连接失败：" + StrUtil.blankToDefault(e.getMessage(), "未知错误") + "）");
        }
    }

    private void checkDatabase(
            SkillHandleValidationVO.Handle handle,
            Driver driver,
            Ctx ctx,
            DataBase dataBase,
            SourceRefResolver.Ref ref) {
        List<String> databases = ctx.databases(driver, dataBase);
        if (databases.contains(lower(ref.getDatabase()))) {
            markOk(handle, ref.getDatabase(), null);
            return;
        }
        handle.setStatus(STATUS_NOT_FOUND);
        handle.setMessage("数据源下没有该库");
    }

    private void checkTable(
            SkillHandleValidationVO.Handle handle,
            Driver driver,
            Ctx ctx,
            DataBase dataBase,
            SourceRefResolver.Ref ref) {
        Set<String> tables = ctx.tables(driver, dataBase, ref.getDatabase());
        if (tables.contains(lower(ref.getTable()))) {
            markOk(handle, ref.getTable(), null);
            return;
        }
        // 库本身不存在时给更准确的提示（否则用户会去表名上找问题）
        List<String> databases = ctx.databases(driver, dataBase);
        if (!databases.isEmpty() && !databases.contains(lower(ref.getDatabase()))) {
            handle.setStatus(STATUS_NOT_FOUND);
            handle.setMessage("数据源下没有该库");
            return;
        }
        handle.setStatus(STATUS_NOT_FOUND);
        handle.setMessage("库下没有该表");
    }

    private void checkColumn(
            SkillHandleValidationVO.Handle handle,
            Driver driver,
            Ctx ctx,
            DataBase dataBase,
            SourceRefResolver.Ref ref) {
        Set<String> tables = ctx.tables(driver, dataBase, ref.getDatabase());
        if (!tables.contains(lower(ref.getTable()))) {
            handle.setStatus(STATUS_NOT_FOUND);
            handle.setMessage("库下没有该表");
            return;
        }
        List<Column> columns = driver.listColumns(ref.getDatabase(), ref.getTable());
        if (CollUtil.isEmpty(columns)) {
            handle.setStatus(STATUS_NOT_FOUND);
            handle.setMessage("表下没有该字段（或该账号无权读取表结构）");
            return;
        }
        for (Column column : columns) {
            if (column != null && StrUtil.equalsIgnoreCase(column.getName(), ref.getColumn())) {
                markOk(handle, column.getName(), null);
                return;
            }
        }
        handle.setStatus(STATUS_NOT_FOUND);
        handle.setMessage("表下没有该字段");
    }

    /** 汇总计数：{@code bareOnly} 与状态无关（bare 形式同样要校验，只是不告警）。 */
    private void count(SkillHandleValidationVO.Summary summary, SkillHandleValidationVO.Handle handle) {
        summary.setTotal(summary.getTotal() + 1);
        if (SYNTAX_BARE.equals(handle.getSyntax())) {
            summary.setBareOnly(summary.getBareOnly() + 1);
        }
        String status = handle.getStatus();
        if (STATUS_NOT_FOUND.equals(status)) {
            summary.setNotFound(summary.getNotFound() + 1);
        } else if (STATUS_DENIED.equals(status)) {
            summary.setDenied(summary.getDenied() + 1);
        } else if (STATUS_UNKNOWN_TYPE.equals(status)) {
            summary.setUnknownType(summary.getUnknownType() + 1);
        } else {
            summary.setOk(summary.getOk() + 1);
        }
    }

    private void markOk(SkillHandleValidationVO.Handle handle, String title, String message) {
        handle.setStatus(STATUS_OK);
        handle.setTitle(title);
        handle.setMessage(message);
    }

    private List<SemanticPublishedProjectVO> safeProjects() {
        List<SemanticPublishedProjectVO> projects = semanticFileService.listPublishedProjects();
        return projects == null ? CollUtil.newArrayList() : projects;
    }

    private List<SemanticPublishedAssetVO> safeAssets(String project) {
        List<SemanticPublishedAssetVO> assets = semanticFileService.listPublishedAssets(project);
        return assets == null ? CollUtil.newArrayList() : assets;
    }

    private boolean isDoc(Skill skill) {
        return "doc".equalsIgnoreCase(skill.getAssetType());
    }

    private String supportedTypes() {
        StringBuilder sb = new StringBuilder();
        for (AssetType type : AssetType.values()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(type.getCode());
        }
        return sb.toString();
    }

    private String lower(String value) {
        return StrUtil.nullToEmpty(value).toLowerCase(Locale.ROOT);
    }

    /**
     * 把围栏代码块替换成等长空白（保留换行）——这样行列号不受影响，
     * 代码块里的示例 {@code @} 也不会被当成真实引用。
     */
    private String maskFencedBlocks(String content) {
        Matcher matcher = FENCED_BLOCK.matcher(content);
        if (!matcher.find()) {
            return content;
        }
        StringBuilder sb = new StringBuilder(content);
        matcher.reset();
        while (matcher.find()) {
            for (int i = matcher.start(); i < matcher.end(); i++) {
                if (sb.charAt(i) != '\n' && sb.charAt(i) != '\r') {
                    sb.setCharAt(i, ' ');
                }
            }
        }
        return sb.toString();
    }

    /** 单次校验内的元数据缓存（同一数据源的库/表清单只查一次）。 */
    private static final class Ctx {

        private Map<String, DataBase> sources;

        private final Map<String, List<String>> databases = new HashMap<>();

        private final Map<String, Set<String>> tables = new HashMap<>();

        Map<String, DataBase> sources(DataBaseService dataBaseService) {
            if (sources == null) {
                sources = SourceRefResolver.index(dataBaseService.listEnabledAll());
            }
            return sources;
        }

        List<String> databases(Driver driver, DataBase dataBase) {
            return databases.computeIfAbsent(dataBase.getName(), key -> {
                List<String> names = CollUtil.newArrayList();
                List<Schema> schemas = driver.listSchemas();
                if (schemas != null) {
                    for (Schema schema : schemas) {
                        if (schema != null && StrUtil.isNotBlank(schema.getName())) {
                            names.add(schema.getName().toLowerCase(Locale.ROOT));
                        }
                    }
                }
                return names;
            });
        }

        Set<String> tables(Driver driver, DataBase dataBase, String database) {
            String key = dataBase.getName() + "|" + StrUtil.nullToEmpty(database);
            return tables.computeIfAbsent(key, ignored -> {
                Set<String> names = new LinkedHashSet<>();
                List<Table> loaded = driver.listTables(database);
                if (loaded != null) {
                    for (Table table : loaded) {
                        if (table != null && StrUtil.isNotBlank(table.getName())) {
                            names.add(table.getName().toLowerCase(Locale.ROOT));
                        }
                    }
                }
                return names;
            });
        }
    }
}
