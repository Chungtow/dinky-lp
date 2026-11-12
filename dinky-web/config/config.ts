/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// https://umijs.org/config/
import { defineConfig } from '@umijs/max';
import { join } from 'path';
import defaultSettings from './defaultSettings';
import proxy from './proxy';
import routes from './routes';

const { REACT_APP_ENV = 'dev' } = process.env;
const MonacoWebpackPlugin = require('monaco-editor-webpack-plugin');
const fs = require('fs');
const BrandAssetGuardPlugin = require('./plugins/brand-asset-guard');

/**
 * @name 品牌（白标）解析
 * @description 构建期由 `build.sh --brand <id>` 经 pom 的 environmentVariables 传入 BRAND，默认 dinky。
 *              元数据在 `dinky-web/brand/<id>.json`，素材在 `dinky-web/public/brand/<id>/`（覆盖式注入 dist 根）。
 * @@doc docs/plan/20261106-Dinky-LP Web端商业化白标多品牌切换实施计划.md
 */
const BRAND_ID = process.env.BRAND || 'dinky';
const BRAND_META_FILE = join(__dirname, '..', 'brand', `${BRAND_ID}.json`);
const BRAND_ASSET_DIR = join(__dirname, '..', 'public', 'brand', BRAND_ID);

if (!fs.existsSync(BRAND_META_FILE)) {
  throw new Error(
    `[brand] 找不到品牌元数据：${BRAND_META_FILE}（可用品牌见 dinky-web/brand/*.json）`
  );
}
const brandMeta = JSON.parse(fs.readFileSync(BRAND_META_FILE, 'utf-8'));

/**
 * @name 品牌主题色
 * @description 色板只认品牌包 `theme` 里声明的值，缺省一律回退 defaultSettings：
 *              ```
 *              "theme": {
 *                "colorPrimary":    "#186C6C",  // 主色：按钮/链接/选中文字/焦点环
 *                "colorAccent":     "#249B87",  // 强调：指示条/Tab 下划线/图表（禁止做按钮底）
 *                "colorBgTint":     "#E4F0E4",  // 浅底：菜单选中底/表头
 *                "colorInk":        "#0F3F3D",  // 深字：大标题
 *                "colorHeaderBg":   "#0E3B39"   // 顶部导航底色
 *              }
 *              ```
 * @@doc docs/plan/20261106-Dinky-LP Web端商业化白标多品牌切换实施计划.md
 */
const BRAND_THEME_CFG = brandMeta?.theme || {};
const BRAND_PRIMARY: string = BRAND_THEME_CFG.colorPrimary || defaultSettings.colorPrimary;

const REQUIRED_ASSETS: string[] = brandMeta?.assets?.required || ['brand-logo.svg', 'favicon.ico'];
const missingAssets = REQUIRED_ASSETS.filter(
  (name: string) => !fs.existsSync(join(BRAND_ASSET_DIR, name))
);
if (missingAssets.length > 0) {
  throw new Error(
    `[brand:${BRAND_ID}] 缺少必选品牌素材：${missingAssets.join(', ')}（目录：${BRAND_ASSET_DIR}）`
  );
}
const RECOMMENDED_ASSETS: string[] = brandMeta?.assets?.recommended || [];
const missingRecommended = RECOMMENDED_ASSETS.filter(
  (name: string) => !fs.existsSync(join(BRAND_ASSET_DIR, name))
);
if (missingRecommended.length > 0) {
  console.warn(
    `[brand:${BRAND_ID}] 推荐素材不在品牌包内，将由 public/ 公共视觉层提供：${missingRecommended.join(
      ', '
    )}`
  );
}
console.log(`[brand] 构建品牌 = ${BRAND_ID}（${brandMeta.documentTitle || brandMeta.name || ''}）`);

/**
 * @name 登录页 Lottie 动画的品牌化（品牌自备优先，否则回退公共层）
 * @description 动画是构建期静态 import（内联进登录页 chunk），文件被放到 dist 里也不会被请求，
 *              因此"同名覆盖"必须在构建期解析：品牌包内存在 `login_animation.json` 就指向它，
 *              否则指向公共层 `public/login_animation.json`。代码侧统一 import '@brand-login-animation'。
 * @@doc docs/user-guide/ops/Dinky-LP 品牌定制与部署操作手册.md（§3.6）
 */
const BRAND_ANIMATION = join(BRAND_ASSET_DIR, 'login_animation.json');
const PUBLIC_ANIMATION = join(__dirname, '..', 'public', 'login_animation.json');
const LOGIN_ANIMATION = fs.existsSync(BRAND_ANIMATION) ? BRAND_ANIMATION : PUBLIC_ANIMATION;
if (!fs.existsSync(LOGIN_ANIMATION)) {
  throw new Error(
    `[brand:${BRAND_ID}] 找不到登录页动画：${LOGIN_ANIMATION}（公共层兜底文件也缺失）`
  );
}
console.log(
  `[brand:${BRAND_ID}] 登录页动画 = ${
    LOGIN_ANIMATION === BRAND_ANIMATION ? '品牌包' : '公共层（品牌包未提供，已回退）'
  } → ${LOGIN_ANIMATION}`
);

/**
 * @name 技术支持二维码清单（构建期扫描品牌包 community/ 目录）
 * @description 「关于」页的技术支持区按这份清单渲染，未提供的渠道直接不渲染，
 *              避免出现 404 空图（例如 liangpu 只有企业微信与钉钉、没有 QQ）。
 *              前端经 `brandCommunity()` 读取（`__BRAND_COMMUNITY__`）。
 */
const COMMUNITY_DIR = join(BRAND_ASSET_DIR, 'community');
const BRAND_COMMUNITY: string[] = fs.existsSync(COMMUNITY_DIR)
  ? fs
      .readdirSync(COMMUNITY_DIR)
      .filter((f) => /\.(png|jpe?g|svg)$/i.test(f))
      .sort()
  : [];
console.log(
  `[brand:${BRAND_ID}] 技术支持二维码 = ${
    BRAND_COMMUNITY.length ? BRAND_COMMUNITY.join(', ') : '（品牌包未提供）'
  }`
);

/**
 * @name 品牌素材投递清单（逐文件展开，替代"整包拷贝"）
 * @description 只投递**真正要给浏览器访问**的素材；品牌包内的 `README.md`（内部说明）
 *              与 `login_animation.json`（构建期已 import 内联进登录页 chunk，线上不请求）
 *              一律不投递，避免内部文档被公开下载、制品体积虚增。
 *              逐文件展开（`from` 只给文件、`to` 一律给目录）可避免"目录拷贝是否带一层"的歧义。
 * @@doc docs/user-guide/ops/Dinky-LP 品牌定制与部署操作手册.md（§3 素材规格）
 */
const BRAND_COPY: { from: string; to: string }[] = [];
const BRAND_COPY_EXCLUDE = ['README.md', 'login_animation.json'];

function collectBrandCopy(): { from: string; to: string }[] {
  if (!fs.existsSync(BRAND_ASSET_DIR)) return [];
  const out: { from: string; to: string }[] = [];
  const walk = (rel: string) => {
    const abs = join(BRAND_ASSET_DIR, rel);
    for (const entry of fs.readdirSync(abs, { withFileTypes: true })) {
      const relPath = rel ? `${rel}/${entry.name}` : entry.name;
      if (entry.isDirectory()) {
        walk(relPath);
        continue;
      }
      if (rel === '' && BRAND_COPY_EXCLUDE.includes(entry.name)) continue;
      out.push({ from: `public/brand/${BRAND_ID}/${relPath}`, to: `dist${rel ? `/${rel}` : ''}` });
    }
  };
  walk('');
  return out;
}
BRAND_COPY.push(...collectBrandCopy());
console.log(
  `[brand:${BRAND_ID}] 投递素材 ${BRAND_COPY.length} 项（不含 ${BRAND_COPY_EXCLUDE.join('、')}）：${BRAND_COPY.map(
    (c) => c.from.replace(`public/brand/${BRAND_ID}/`, '')
  ).join(', ')}`
);

// @ts-ignore
export default defineConfig({
  /**
   * tags router：
   * doc description: https://juejin.cn/post/7153525746751766559
   * issue: https://www.github.com/ant-design/ant-design-pro/issues/10550
   */
  /* keepalive: [/./],
   tabsLayout: {
      hasDropdown: true, // Whether there is a drop-down menu
      hasCustomTabs: true, // Whether there are custom tabs
      hasFixedHeader: true, // Whether there is a fixed header
   },*/

  /**
   * @name 开启 hash 模式
   * @description 让 build 之后的产物包含 hash 后缀。通常用于增量发布和避免浏览器加载缓存。
   * @doc https://umijs.org/docs/api/config#hash
   */
  hash: true,
  esbuildMinifyIIFE: true,
  publicPath: process.env.NODE_ENV === 'production' ? './' : '/',
  history: {
    type: 'hash'
  },
  /**
   * @name 兼容性设置
   * @description 设置 ie11 不一定完美兼容，需要检查自己使用的所有依赖
   * @doc https://umijs.org/docs/api/config#targets
   */
  // targets: {
  //   ie: 11,
  // },
  /**
   * @name 路由的配置，不在路由中引入的文件不会编译
   * @description 只支持 path，component，routes，redirect，wrappers，title 的配置
   * @doc https://umijs.org/docs/guides/routes
   */
  // umi routes: https://umijs.org/docs/routing
  routes,
  /**
   * @name 主题的配置
   * @description 虽然叫主题，但是其实只是 less 的变量设置
   * @doc antd的主题设置 https://ant.design/docs/react/customize-theme-cn
   * @doc umi 的theme 配置 https://umijs.org/docs/api/config#theme
   */
  theme: {
    // 如果不想要 configProvide 动态设置主题需要把这个设置为 default
    // 只有设置为 variable， 才能使用 configProvide 动态设置主色调
    'root-entry-name': 'variable'
  },
  /**
   * @name moment 的国际化配置
   * @description 如果对国际化没有要求，打开之后能减少js的包大小
   * @doc https://umijs.org/docs/api/config#ignoremomentlocale
   */
  ignoreMomentLocale: true,
  /**
   * @name 代理配置
   * @description 可以让你的本地服务器代理到你的服务器上，这样你就可以访问服务器的数据了
   * @see 要注意以下 代理只能在本地开发时使用，build 之后就无法使用了。
   * @doc 代理介绍 https://umijs.org/docs/guides/proxy
   * @doc 代理配置 https://umijs.org/docs/api/config#proxy
   */
  // @ts-ignore
  proxy: proxy[REACT_APP_ENV as keyof typeof proxy],
  /**
   * @name 快速热更新配置
   * @description 一个不错的热更新组件，更新时可以保留 state
   */
  fastRefresh: true,
  //============== 以下都是max的插件配置 ===============
  /**
   * @name 数据流插件
   * @@doc https://umijs.org/docs/max/data-flow
   */
  model: {},
  /**
   * 一个全局的初始数据流，可以用它在插件之间共享数据
   * @description 可以用来存放一些全局的数据，比如用户信息，或者一些全局的状态，全局初始状态在整个 Umi 项目的最开始创建。
   * @doc https://umijs.org/docs/max/data-flow#%E5%85%A8%E5%B1%80%E5%88%9D%E5%A7%8B%E7%8A%B6%E6%80%81
   */
  initialState: {},
  /**
   * @name layout 插件
   * @doc https://umijs.org/docs/max/layout-menu
   */
  title: brandMeta.documentTitle || 'Dinky',
  /**
   * @name 品牌素材覆盖注入
   * @description 把 `public/brand/<id>/` 下的素材注入到 dist **根**（与 public/ 同名文件"后者覆盖"），
   *              因此代码里的 `./brand-logo.svg`、`./icons/footer-bg.svg` 等引用路径无需改动。
   *              清单由 `collectBrandCopy()` 逐文件生成：只投递浏览器真正访问的素材，
   *              品牌包内的 README.md / login_animation.json 不投递（见 BRAND_COPY_EXCLUDE）。
   *              `to` 必须是含 dist 的相对路径（umi 内部按 cwd 解析）。
   */
  copy: BRAND_COPY,
  /**
   * @name 登录页动画的品牌解析
   * @description 指向"品牌包内 login_animation.json（存在时）"或"公共层同名文件"，
   *              代码里统一 `import DataPlatform from '@brand-login-animation'`。
   */
  alias: {
    '@brand-login-animation$': LOGIN_ANIMATION
  },
  /**
   * @name 注入浏览器端品牌常量
   * @description `src/branding/index.ts` 从这里取值，组件与 i18n 文案统一引用 BRAND。
   */
  define: {
    __BRAND__: brandMeta,
    __BRAND_COMMUNITY__: BRAND_COMMUNITY
  },
  layout: {
    locale: true,
    ...defaultSettings,
    // 品牌化：title 用于登录页标签页（HelmetTitle 读 Settings.title）；colorPrimary 为品牌主色
    title: brandMeta.nameEn || defaultSettings.title,
    colorPrimary: BRAND_PRIMARY,
    /**
     * 品牌化 token：只在品牌包显式提供了对应色值时才覆盖，
     * 未提供的品牌（如 dinky）完全沿用 defaultSettings，观感零变化。
     * - colorBgTint  → 侧边菜单选中底（原硬编码 #3399FF 亮蓝，与任何绿/木系品牌都冲突）
     * - colorHeaderBg → 顶部导航底色
     */
    token: {
      ...defaultSettings.token,
      header: {
        ...defaultSettings.token?.header,
        ...(BRAND_THEME_CFG.colorHeaderBg ? { colorBgHeader: BRAND_THEME_CFG.colorHeaderBg } : {})
      },
      sider: {
        ...defaultSettings.token?.sider,
        ...(BRAND_THEME_CFG.colorBgTint
          ? { colorBgMenuItemSelected: BRAND_THEME_CFG.colorBgTint }
          : {})
      }
    }
  },
  /**
   * @name moment2dayjs 插件
   * @description 将项目中的 moment 替换为 dayjs
   * @doc https://umijs.org/docs/max/moment2dayjs
   */
  moment2dayjs: {
    preset: 'antd',
    plugins: ['duration']
  },
  /**
   * @name 国际化插件
   * @doc https://umijs.org/docs/max/i18n
   */
  locale: {
    // default zh-CN
    default: 'zh-CN',
    antd: true,
    // default true, when it is true, will use `navigator.language` overwrite default
    baseNavigator: false,
    useLocalStorage: true
  },

  /**
   *  @name styled-components 插件
   */
  // styledComponents: {},

  /**
   * @name antd 插件
   * @description 内置了 babel import 插件
   * @doc https://umijs.org/docs/max/antd#antd
   */
  /**
   * @name antd 主题（根治项）
   * @description 品牌主色此前只进了 ProLayout 的 `layout.colorPrimary`，antd 组件（按钮/链接/复选框/
   *              分页等）仍在用 antd 默认蓝 `#1677ff`，导致"菜单一个色、按钮另一个色"。
   *              这里把品牌主色注入 antd ConfigProvider，链接色一并跟随，避免出现"按钮绿、链接蓝"。
   *              DataStudio 自带独立 ConfigProvider（`pages/DataStudio/themeToken.ts`），不受影响。
   */
  antd: {
    theme: {
      token: {
        colorPrimary: BRAND_PRIMARY,
        colorLink: BRAND_PRIMARY,
        colorInfo: BRAND_PRIMARY
      }
    }
  },
  /**
   * @name 网络请求配置
   * @description 它基于 axios 和 ahooks 的 useRequest 提供了一套统一的网络请求和错误处理方案。
   * @doc https://umijs.org/docs/max/request
   */
  request: {
    dataField: 'data'
  },
  /**
   * @name 权限插件
   * @description 基于 initialState 的权限插件，必须先打开 initialState
   * @doc https://umijs.org/docs/max/access
   */
  access: {},
  /**
   * @name <head> 中额外的 script
   * @description 配置 <head> 中额外的 script
   */
  headScripts: [
    // 解决首次加载时白屏的问题
    { src: './scripts/loading.js', async: true }
  ],
  //================ pro 插件配置 =================
  presets: ['umi-presets-pro'],
  /**
   * @name openAPI 插件的配置
   * @description 基于 openapi 的规范生成serve 和mock，能减少很多样板代码
   * @doc https://pro.ant.design/zh-cn/docs/openapi/
   */
  openAPI: [
    {
      requestLibPath: "import { request } from '@umijs/max'",
      // 或者使用在线的版本
      // schemaPath: "https://gw.alipayobjects.com/os/antfincdn/M%24jrzTTYJN/oneapi.json"
      schemaPath: join(__dirname, 'oneapi.json'),
      mock: false
    },
    {
      requestLibPath: "import { request } from '@umijs/max'",
      schemaPath: 'https://gw.alipayobjects.com/os/antfincdn/CA1dOm%2631B/openapi.json',
      projectName: 'swagger'
    }
  ],
  mfsu: {
    strategy: 'eager'
  },
  // requestRecord: {},
  dva: {},

  chainWebpack: (memo) => {
    memo.plugin('monaco-editor-webpack-plugin').use(MonacoWebpackPlugin, [
      // 按需配置
      { languages: ['shell', 'json', 'java', 'sql', 'python', 'scala', 'yaml'] }
    ]);

    // 品牌素材覆盖注入的 fail-fast 校验（logo 必须已落到 dist 根）；
    // 品牌源目录 dist/brand/ 由 dinky-admin 的 copy-static exclude + build.sh 清理，不进发布包
    memo
      .plugin('brand-asset-guard')
      .use(BrandAssetGuardPlugin, [{ assetProbe: brandMeta.logo || 'brand-logo.svg' }]);

    return memo;
  }
});
