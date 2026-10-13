/**
 * DataX 反向同步（Hive/Spark → MySQL）阶段一 —— 回归测试
 *
 * 零新依赖（复用 dinky-web 自带的 typescript / ts-node），运行方式：
 *
 *     cd dinky-web
 *     npx ts-node -T -O '{"module":"commonjs","esModuleInterop":true}' scripts/dataxJson.test.ts
 *
 * 用例来源于 2026-10-12 UAT 实际踩到的坑，
 * 详见 docs/review/20261012-dinky-DataX反向同步阶段一-复盘.md：
 *
 *   1. location 只剩 scheme（回填污染值）不得参与路径推导  → 否则 reader.path 退化为 /pt=xxx
 *   2. 反向 reader.path 必须带表目录                      → 否则运行期报 HdfsReader-09
 *   3. 回填 → 再生成 必须稳定（幂等）                      → 否则"越修越坏"
 *   4. 回填不得把旧 path 写回 config.hdfsPath（短路根因）
 *   5. 正向 writer.path 行为与历史一致（本次改动的回归保护）
 */
import {
  buildDataXJson,
  buildDefaultHdfsPath,
  buildDefaultMappings,
  buildHdfsPath,
  buildHdfsReaderColumn,
  extractSchemaFromHdfsPath,
  extractTableFromHdfsPath,
  isValidTableLocation,
  mapToDataxType,
  parseDataXJson,
  parseHdfsPathInfo,
  type DataXField,
  type DataXVisualConfig
} from '../src/pages/DataStudio/CenterTabContent/SqlTask/DataXVisualEditor/dataxJson';

/* ---------------------------------------------------------------- 极简断言器 */

let passed = 0;
const failures: string[] = [];

function group(title: string): void {
  console.log(`\n${title}`);
}

function t(name: string, fn: () => void): void {
  try {
    fn();
    passed += 1;
    console.log(`  ✓ ${name}`);
  } catch (e) {
    failures.push(name);
    console.log(`  ✗ ${name}\n      ${(e as Error).message}`);
  }
}

function eq(actual: unknown, expected: unknown, hint = ''): void {
  const a = JSON.stringify(actual);
  const b = JSON.stringify(expected);
  if (a !== b) {
    throw new Error(`${hint}\n      实际: ${a}\n      期望: ${b}`);
  }
}

function ok(cond: boolean, hint = '断言失败'): void {
  if (!cond) {
    throw new Error(hint);
  }
}

/* ---------------------------------------------------------------- 构造样本 */

const DFS = 'hdfs://mycluster';

/** 反向（Hive → MySQL）配置：镜像 UAT 中的 lpdw.ads_traccar_stat → traccar.ads_traccar_stat */
function reverseConfig(sourceLocation: string, extra: Partial<DataXVisualConfig> = {}): DataXVisualConfig {
  return {
    direction: 'hive2mysql',
    defaultFS: DFS,
    fileType: 'orc',
    writeMode: 'update',
    updateKey: ['user_id', 'pt'],
    source: {
      dataSourceId: 19,
      dataSourceName: 'STS-lpdw',
      schemaName: 'lpdw',
      tableName: 'ads_traccar_stat',
      fields: [
        { name: 'user_id', type: 'bigint' },
        { name: 'user_name', type: 'string' },
        { name: 'device_cnt', type: 'bigint' },
        { name: 'stat_date', type: 'string' },
        { name: 'pt', type: 'string' }
      ],
      location: sourceLocation,
      partitionColumns: [{ name: 'pt', type: 'string' }],
      partitionColumn: 'pt',
      partitionExpression: '${BIZ_DATE}'
    },
    target: {
      dataSourceId: 20,
      dataSourceName: 'traccar-mysql',
      schemaName: 'traccar',
      tableName: 'ads_traccar_stat',
      fields: [
        { name: 'user_id', type: 'bigint' },
        { name: 'user_name', type: 'varchar' },
        { name: 'device_cnt', type: 'bigint' },
        { name: 'pt', type: 'varchar' }
      ],
      jdbcUrl: 'jdbc:mysql://manager:33306/traccar',
      username: 'root',
      password: 'x'
    },
    mappings: [
      { sourceField: 'user_id', targetField: 'user_id' },
      { sourceField: 'user_name', targetField: 'user_name' },
      { sourceField: 'device_cnt', targetField: 'device_cnt' },
      { sourceField: 'stat_date', targetField: 'pt' }
    ],
    ...extra
  } as DataXVisualConfig;
}

/** 正向（MySQL → Hive）配置 */
function forwardConfig(targetLocation: string, partitionColumn?: string): DataXVisualConfig {
  return {
    direction: 'mysql2hive',
    defaultFS: DFS,
    fileType: 'orc',
    source: {
      schemaName: 'traccar',
      tableName: 'tc_devices',
      fields: [
        { name: 'id', type: 'bigint' },
        { name: 'name', type: 'string' }
      ],
      jdbcUrl: 'jdbc:mysql://manager:33306/traccar',
      username: 'root',
      password: 'x'
    },
    target: {
      schemaName: 'lpods',
      tableName: 'ods_traccar_tc_devices',
      fields: [
        { name: 'id', type: 'bigint' },
        { name: 'name', type: 'string' }
      ],
      location: targetLocation,
      partitionColumn,
      partitionExpression: partitionColumn ? '${SYNC_DATE}' : undefined
    },
    mappings: [
      { sourceField: 'id', targetField: 'id' },
      { sourceField: 'name', targetField: 'name' }
    ]
  } as DataXVisualConfig;
}

function readerParam(jsonStr: string): Record<string, any> {
  return JSON.parse(jsonStr).job.content[0].reader.parameter;
}

function writerParam(jsonStr: string): Record<string, any> {
  return JSON.parse(jsonStr).job.content[0].writer.parameter;
}

/* ---------------------------------------------------------------- 1. location 有效性 */

group('1) isValidTableLocation —— 残缺 location 识别（曾导致 reader.path 丢表目录）');

t('空串 → 无效', () => ok(isValidTableLocation('') === false));
t('空白串 → 无效', () => ok(isValidTableLocation('   ') === false));
t('undefined → 无效', () => ok(isValidTableLocation(undefined) === false));
t('只有 scheme（回填污染值 hdfs://mycluster）→ 无效', () =>
  ok(isValidTableLocation(DFS) === false, '只剩 scheme 必须判为无效，否则 path 会退化为 /pt=xxx'));
t('仅根路径 / → 无效', () => ok(isValidTableLocation('/') === false));
t('完整地址 → 有效', () => ok(isValidTableLocation(`${DFS}/user/hive/warehouse/lpods.db/ods_x`) === true));
t('无 scheme 的表路径 → 有效', () => ok(isValidTableLocation('/user/hive/warehouse/lpods.db/ods_x') === true));

/* ---------------------------------------------------------------- 2. 路径组装 */

group('2) 路径组装（buildDefaultHdfsPath / buildHdfsPath）');

t('默认路径模板', () => eq(buildDefaultHdfsPath('lpdw', 'ads_x'), '/user/hive/warehouse/lpdw.db/ads_x'));
t('剥掉 defaultFS 前缀并拼分区段', () =>
  eq(buildHdfsPath(`${DFS}/user/hive/warehouse/lpdw.db/ads_x`, DFS, 'pt', '${BIZ_DATE}'), '/user/hive/warehouse/lpdw.db/ads_x/pt=${BIZ_DATE}'));
t('剥掉其它 scheme', () =>
  eq(buildHdfsPath('hdfs://other/user/hive/warehouse/a.db/b', DFS, 'pt', 'X'), '/user/hive/warehouse/a.db/b/pt=X'));
t('无分区列时不拼接分区段', () =>
  eq(buildHdfsPath('/user/hive/warehouse/a.db/b', '', undefined, undefined), '/user/hive/warehouse/a.db/b'));

/* ---------------------------------------------------------------- 3. 反向 reader.path */

group('3) 反向 reader.path —— 必须带表目录（曾报 HdfsReader-09）');

const REVERSE_EXPECT = '/user/hive/warehouse/lpdw.db/ads_traccar_stat/pt=${BIZ_DATE}';

t('source.location 为空（MANAGED 表，show create table 无 LOCATION）', () =>
  eq(readerParam(buildDataXJson(reverseConfig(''))).path, REVERSE_EXPECT));
t('source.location 为回填污染值 hdfs://mycluster', () =>
  eq(readerParam(buildDataXJson(reverseConfig(DFS))).path, REVERSE_EXPECT, '只剩 scheme 的 location 必须回退到默认 warehouse 路径'));
t('source.location 为完整地址', () =>
  eq(readerParam(buildDataXJson(reverseConfig(`${DFS}/user/hive/warehouse/lpdw.db/ads_traccar_stat`))).path, REVERSE_EXPECT));
t('显式自定义 hdfsPath 优先于 location 推导', () =>
  eq(readerParam(buildDataXJson(reverseConfig('', { hdfsPath: '/custom/path/pt=${BIZ_DATE}' }))).path, '/custom/path/pt=${BIZ_DATE}'));
t('reader=hdfsreader、writer=mysqlwriter', () => {
  const j = JSON.parse(buildDataXJson(reverseConfig('')));
  eq(j.job.content[0].reader.name, 'hdfsreader');
  eq(j.job.content[0].writer.name, 'mysqlwriter');
});

/* ---------------------------------------------------------------- 4. 往返稳定性（核心） */

group('4) 回填 → 再生成 稳定性（曾"越修越坏"）');

t('反向：回填不得把旧 path 写回 config.hdfsPath（短路根因）', () => {
  const j1 = buildDataXJson(reverseConfig(''));
  const c2 = parseDataXJson(j1) as DataXVisualConfig;
  ok(c2 !== null, 'parseDataXJson 不应返回 null');
  ok(!c2.hdfsPath, `回填后 config.hdfsPath 应为空，实际=${c2.hdfsPath}（非空将短路路径推导）`);
});

t('反向：以污染值起步，往返两次后 path 仍正确且稳定', () => {
  const j1 = buildDataXJson(reverseConfig(DFS));
  eq(readerParam(j1).path, REVERSE_EXPECT, '首次生成即应纠正');
  const c2 = parseDataXJson(j1) as DataXVisualConfig;
  const j2 = buildDataXJson(c2);
  eq(readerParam(j2).path, REVERSE_EXPECT, '第二次生成 path 退化');
  const c3 = parseDataXJson(j2) as DataXVisualConfig;
  const j3 = buildDataXJson(c3);
  eq(readerParam(j3).path, REVERSE_EXPECT, '第三次生成 path 退化');
});

t('反向：往返后 updateKey / writeMode 保持，writer.column 幂等', () => {
  const c2 = parseDataXJson(buildDataXJson(reverseConfig(''))) as DataXVisualConfig;
  const j2 = buildDataXJson(c2);
  eq(writerParam(j2).updateKey, ['user_id', 'pt']);
  eq(writerParam(j2).writeMode, 'update');
  const c3 = parseDataXJson(j2) as DataXVisualConfig;
  const j3 = buildDataXJson(c3);
  eq(writerParam(j3).column, writerParam(j2).column, '往返后 writer.column 应稳定（幂等）');
});

t('反向：往返后 writer.column 与首次完全一致（映射不会因往返膨胀）', () => {
  const j1 = buildDataXJson(reverseConfig(''));
  const c2 = parseDataXJson(j1) as DataXVisualConfig;
  const j2 = buildDataXJson(c2);
  eq(writerParam(j1).column, ['user_id', 'user_name', 'device_cnt', 'pt']);
  eq(writerParam(j2).column, writerParam(j1).column, '往返后 writer.column 应完全一致');
});

t('反向：dinkyMeta.mappings 原样恢复（含跨名映射 stat_date → pt）', () => {
  const j1 = buildDataXJson(reverseConfig(''));
  const c2 = parseDataXJson(j1) as DataXVisualConfig;
  eq(c2.mappings, [
    { sourceField: 'user_id', targetField: 'user_id' },
    { sourceField: 'user_name', targetField: 'user_name' },
    { sourceField: 'device_cnt', targetField: 'device_cnt' },
    { sourceField: 'stat_date', targetField: 'pt' }
  ]);
});

t('分区列不参与自动配对（buildDefaultMappings 排除源端分区列）', () => {
  const src: DataXField[] = [
    { name: 'user_id', type: 'bigint' },
    { name: 'stat_date', type: 'string' },
    { name: 'pt', type: 'string' }
  ];
  const tgt: DataXField[] = [
    { name: 'user_id', type: 'bigint' },
    { name: 'pt', type: 'varchar' }
  ];
  eq(buildDefaultMappings(src, tgt, ['pt']), [{ sourceField: 'user_id', targetField: 'user_id' }]);
  // 不传排除列表时语义保持朴素：同名即配对
  eq(buildDefaultMappings(src, tgt), [
    { sourceField: 'user_id', targetField: 'user_id' },
    { sourceField: 'pt', targetField: 'pt' }
  ]);
});

t('正向：往返后 writer.path 稳定', () => {
  const j1 = buildDataXJson(forwardConfig(`${DFS}/user/hive/warehouse/lpods.db/ods_traccar_tc_devices`, 'pt'));
  const c2 = parseDataXJson(j1) as DataXVisualConfig;
  ok(!c2.hdfsPath, '正向回填同样不得写回 hdfsPath');
  const j2 = buildDataXJson(c2);
  eq(writerParam(j2).path, writerParam(j1).path, '往返后 writer.path 应稳定');
});

/* ---------------------------------------------------------------- 5. 正向路径回归 */

group('5) 正向 writer.path —— 本次改动不得影响正向（MySQL → Hive）');

const FORWARD_EXPECT = '/user/hive/warehouse/lpods.db/ods_traccar_tc_devices/pt=${SYNC_DATE}';

t('target.location 完整', () =>
  eq(writerParam(buildDataXJson(forwardConfig(`${DFS}/user/hive/warehouse/lpods.db/ods_traccar_tc_devices`, 'pt'))).path, FORWARD_EXPECT));
t('target.location 为空（新建表 / MANAGED）', () =>
  eq(writerParam(buildDataXJson(forwardConfig('', 'pt'))).path, FORWARD_EXPECT));
t('target.location 为污染值 hdfs://mycluster', () =>
  eq(writerParam(buildDataXJson(forwardConfig(DFS, 'pt'))).path, FORWARD_EXPECT));
t('无分区表不拼分区段', () =>
  eq(writerParam(buildDataXJson(forwardConfig(''))).path, '/user/hive/warehouse/lpods.db/ods_traccar_tc_devices'));
t('正向 reader=mysqlreader、writer=hdfswriter（wrapper 仍走建目录分支）', () => {
  const j = JSON.parse(buildDataXJson(forwardConfig('', 'pt')));
  eq(j.job.content[0].reader.name, 'mysqlreader');
  eq(j.job.content[0].writer.name, 'hdfswriter');
});

/* ---------------------------------------------------------------- 6. location 解析 */

group('6) parseHdfsPathInfo —— 不得拼出"只有 scheme"的 location');

t('path 只剩分区段时不拼 defaultFS', () =>
  eq(parseHdfsPathInfo('/pt=${BIZ_DATE}', DFS).location, '', '空 location 若被拼成 defaultFS，回填会永久污染'));
t('正常 path 解析出 location + 分区字段 + 表达式', () => {
  const r = parseHdfsPathInfo('/user/hive/warehouse/a.db/b/pt=20261011', DFS);
  eq(r.location, `${DFS}/user/hive/warehouse/a.db/b`);
  eq(r.partitionColumn, 'pt');
  eq(r.partitionExpression, '20261011');
});
t('无分区段的 path 原样返回', () =>
  eq(parseHdfsPathInfo(`${DFS}/user/hive/warehouse/a.db/b`, DFS).location, `${DFS}/user/hive/warehouse/a.db/b`));

/* ---------------------------------------------------------------- 7. 字段与列构造 */

group('7) 字段与列构造');

t('mapToDataxType 基础映射（bigint → LONG / string → STRING）', () => {
  eq(mapToDataxType('bigint'), 'LONG');
  eq(mapToDataxType('string'), 'STRING');
});
t('mapToDataxType 对未知类型不抛错且返回非空', () => {
  const v = mapToDataxType('some_unknown_type');
  ok(typeof v === 'string' && v.length > 0, `期望非空字符串，实际=${JSON.stringify(v)}`);
});
t('buildHdfsReaderColumn 按字段顺序给 index（hdfsreader 不接受字符串数组）', () => {
  const cols = buildHdfsReaderColumn(
    [
      { name: 'user_id', type: 'bigint' },
      { name: 'user_name', type: 'string' }
    ],
    ['user_id', 'user_name']
  );
  eq(cols, [
    { index: 0, type: 'LONG' },
    { index: 1, type: 'STRING' }
  ]);
});
t('extractSchemaFromHdfsPath', () => eq(extractSchemaFromHdfsPath('/user/hive/warehouse/lpdw.db/ads_x'), 'lpdw'));
t('extractTableFromHdfsPath（含分区段）', () =>
  eq(extractTableFromHdfsPath('/user/hive/warehouse/lpdw.db/ads_x/pt=1'), 'ads_x'));

/* ---------------------------------------------------------------- 汇总 */

console.log(`\n${'-'.repeat(64)}`);
if (failures.length === 0) {
  console.log(`全部通过：${passed} 项`);
  process.exit(0);
} else {
  console.log(`通过 ${passed} 项，失败 ${failures.length} 项：`);
  failures.forEach((f) => console.log(`  - ${f}`));
  process.exit(1);
}
