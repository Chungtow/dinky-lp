/**
 * 「一键 DataX 同步」阶段二（反向 + 目的地画像）回归测试
 *
 * 零新依赖，运行方式：
 *     cd dinky-web
 *     npx ts-node -T -O '{"module":"commonjs","esModuleInterop":true}' scripts/quickSync.test.ts
 *
 * 覆盖：
 *   1. 目标表名前缀规则（计划 §3.7）：取右键目录名小写前 3 位，非法字符移除，空则回退 ods
 *   2. 目的地画像（计划 §4.8）：Hive 需预建分区目录 + 刷统计；MySQL 都不需要
 *   3. **正向等价性**：Hive 画像必须生成与改造前完全相同的 6 个步骤
 *   4. 反向作业集：MySQL 目的地只生成 建表 + 同步（2 个作业）
 */
import {
  buildDefaultTargetTableName,
  buildQuickSyncSteps,
  buildTableNamePrefix,
  HIVE_PROFILE,
  MYSQL_PROFILE,
  profileFromDestinationType
} from '../src/pages/DataStudio/Toolbar/Project/DataXQuickSyncModal/constants';
import { buildMysqlCreateTableDdl } from '../src/pages/DataStudio/Toolbar/Project/DataXQuickSyncModal/statements';
import { mapToMysqlType } from '../src/pages/DataStudio/Toolbar/Project/DataXQuickSyncModal/typeMapping';

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

/* ---------------------------------------------------------------- 1. 表名前缀 */

group('1) 目标表名前缀规则（<目录名小写前3位>_<源库>_<源表>，计划 §3.7）');

t('ODS → ods', () => eq(buildTableNamePrefix('ODS'), 'ods'));
t('APP → app', () => eq(buildTableNamePrefix('APP'), 'app'));
t('ODS_LAYER → ods（只取前 3 位）', () => eq(buildTableNamePrefix('ODS_LAYER'), 'ods'));
t('dwd → dwd', () => eq(buildTableNamePrefix('dwd'), 'dwd'));
t('123 → 123（纯数字合法）', () => eq(buildTableNamePrefix('123'), '123'));
t('a-b → ab（非法字符移除，不替换为下划线）', () => eq(buildTableNamePrefix('a-b'), 'ab'));
t('数仓ODS → ods（中文移除后取前3位）', () => eq(buildTableNamePrefix('数仓ODS'), 'ods'));
t('纯中文 → ods（清洗后为空回退）', () => eq(buildTableNamePrefix('用户统计'), 'ods'));
t('空串 → ods（回退）', () => eq(buildTableNamePrefix(''), 'ods'));
t('undefined → ods（回退）', () => eq(buildTableNamePrefix(undefined), 'ods'));
t('组合：ODS + traccar + tc_devices', () =>
  eq(buildDefaultTargetTableName('ODS', 'traccar', 'tc_devices'), 'ods_traccar_tc_devices'));
t('组合：APP + lpdw + ads_x（反向同样适用）', () =>
  eq(buildDefaultTargetTableName('APP', 'lpdw', 'ads_x'), 'app_lpdw_ads_x'));

/* ---------------------------------------------------------------- 2. 目的地画像 */

group('2) 目的地画像（计划 §4.8）');

t('Hive：需要建表 + 预建分区目录 + 刷统计，不支持 upsert', () => {
  const p = profileFromDestinationType('hive');
  ok(!!p, 'Hive 画像不应为 undefined');
  eq(p!.kind, 'hive');
  eq(p!.needsCreateTable, true);
  eq(p!.requiresPreCreatedPath, true);
  eq(p!.requiresStatsRefresh, true);
  eq(p!.supportsUpsert, false);
});
t('Spark 等同 Hive', () => eq(profileFromDestinationType('spark')!.kind, 'hive'));
t('Hive 非分区表：仍需刷统计，但不需要预建分区目录', () => {
  const p = profileFromDestinationType('hive', false);
  eq(p!.requiresPreCreatedPath, false);
  eq(p!.requiresStatsRefresh, true);
});
t('MySQL：建表可以，但无需预建目录 / 刷统计；支持 upsert', () => {
  const p = profileFromDestinationType('mysql');
  eq(p!.kind, 'mysql');
  eq(p!.needsCreateTable, true);
  eq(p!.requiresPreCreatedPath, false);
  eq(p!.requiresStatsRefresh, false);
  eq(p!.supportsUpsert, true);
});
t('不支持的类型 → undefined（由校验拦截并提示）', () => {
  eq(profileFromDestinationType('kafka'), undefined);
  eq(profileFromDestinationType(''), undefined);
  eq(profileFromDestinationType(undefined), undefined);
});

/* ---------------------------------------------------------------- 3. 正向等价性 */

group('3) 正向等价性（Hive 目的地必须与改造前完全一致）');

const FORWARD_KEYS = ['subFolder', 'createTableJob', 'execCreateTable', 'partitionJob', 'syncJob', 'analyzeJob'];

t('Hive 画像生成 6 个步骤，顺序与名称与改造前一致', () =>
  eq(buildQuickSyncSteps(HIVE_PROFILE).map((s) => s.key), FORWARD_KEYS));
t('Hive 非分区表：跳过 partitionJob，保留 analyzeJob（5 个步骤）', () =>
  eq(
    buildQuickSyncSteps({ ...HIVE_PROFILE, requiresPreCreatedPath: false }).map((s) => s.key),
    ['subFolder', 'createTableJob', 'execCreateTable', 'syncJob', 'analyzeJob']
  ));
t('步骤 label 保持原文（界面进度展示不变）', () => {
  const steps = buildQuickSyncSteps(HIVE_PROFILE);
  eq(steps[0].label, '创建 / 复用子目录');
  eq(steps[3].label, '创建加分区作业（建出分区目录）');
  eq(steps[5].label, '创建统计作业（ANALYZE）');
});

/* ---------------------------------------------------------------- 4. 反向作业集 */

group('4) 反向作业集（MySQL 目的地）');

t('MySQL 画像只生成 4 个步骤 = 建表 + 同步（2 个作业）', () =>
  eq(
    buildQuickSyncSteps(MYSQL_PROFILE).map((s) => s.key),
    ['subFolder', 'createTableJob', 'execCreateTable', 'syncJob']
  ));
t('不含 add_partition / analyze（两者是 Hive 目的地特有）', () => {
  const keys = buildQuickSyncSteps(MYSQL_PROFILE).map((s) => s.key);
  ok(!keys.includes('partitionJob'), '反向不应生成加分区作业');
  ok(!keys.includes('analyzeJob'), '反向不应生成统计作业');
});

/* ---------------------------------------------------------------- 5. 反向建表 DDL */

group('5) 反向建表 DDL：Hive 类型必须转换为 MySQL 类型（阶段二 UAT 反馈）');

t('mapToMysqlType 基础映射', () => {
  eq(mapToMysqlType('bigint'), 'BIGINT');
  eq(mapToMysqlType('int'), 'INT');
  eq(mapToMysqlType('string'), 'VARCHAR(255)');
  eq(mapToMysqlType('timestamp'), 'DATETIME');
  eq(mapToMysqlType('date'), 'DATE');
  eq(mapToMysqlType('boolean'), 'TINYINT(1)');
  eq(mapToMysqlType('decimal(10,2)'), 'DECIMAL(10,2)');
});

t('mapToMysqlType 幂等（已是 MySQL 类型时不变形）', () => {
  eq(mapToMysqlType('VARCHAR(255)'), 'VARCHAR(255)');
  eq(mapToMysqlType('BIGINT'), 'BIGINT');
  eq(mapToMysqlType('DECIMAL(10,2)'), 'DECIMAL(10,2)');
  eq(mapToMysqlType('DATETIME'), 'DATETIME');
});

t('stringLength 可配置，超长落 TEXT', () => {
  eq(mapToMysqlType('string', { stringLength: 100 }), 'VARCHAR(100)');
  eq(mapToMysqlType('string', { stringLength: 8000 }), 'TEXT');
});

t('DDL：类型为 MySQL 类型、主键 NOT NULL、非主键 DEFAULT NULL', () => {
  const ddl = buildMysqlCreateTableDdl({
    schemaName: 'traccar',
    tableName: 'app_lpdw_ads_traccar_stat',
    columns: [
      { name: 'user_id', type: mapToMysqlType('bigint'), comment: '用户ID' },
      { name: 'user_name', type: mapToMysqlType('string'), comment: '用户名称' },
      { name: 'stat_date', type: mapToMysqlType('string'), comment: '统计日期' }
    ],
    primaryKey: ['user_id'],
    comment: 'DataX 同步自 lpdw.ads_traccar_stat'
  });
  ok(ddl.includes('`user_id` BIGINT NOT NULL'), '主键应为 BIGINT NOT NULL：\n' + ddl);
  ok(ddl.includes('`user_name` VARCHAR(255) DEFAULT NULL'), '应为 VARCHAR(255)：\n' + ddl);
  ok(ddl.includes('`stat_date` VARCHAR(255) DEFAULT NULL'), '应为 VARCHAR(255)：\n' + ddl);
  ok(ddl.includes('PRIMARY KEY (`user_id`)'), '主键子句缺失：\n' + ddl);
  ok(ddl.includes('ENGINE = InnoDB'), ddl);
  ok(ddl.includes('DEFAULT CHARSET = utf8mb4'), ddl);
  ok(!ddl.includes('STRING'), 'DDL 不应残留 Hive 类型 STRING：\n' + ddl);
});

t('DDL：复合主键顺序与列名保持', () => {
  const ddl = buildMysqlCreateTableDdl({
    schemaName: 'traccar',
    tableName: 't',
    columns: [
      { name: 'user_id', type: 'BIGINT' },
      { name: 'pt', type: 'VARCHAR(8)' }
    ],
    primaryKey: ['user_id', 'pt']
  });
  ok(ddl.includes('PRIMARY KEY (`user_id`, `pt`)'), ddl);
});

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
