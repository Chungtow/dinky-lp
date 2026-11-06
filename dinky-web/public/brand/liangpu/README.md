# 品牌素材目录：liangpu（预留）

本目录对应品牌 `liangpu`（元数据：`dinky-web/brand/liangpu.json`，**批二创建**）。

## 目录契约

内部结构与 `public/` **逐层对应**，本目录的内容会被覆盖式注入到产物 `dist/` 根：

| 本目录路径 | 产物路径 | 覆盖对象 |
|---|---|---|
| `brand-logo.svg` | `dist/brand-logo.svg` | 无（官方 logo 在 `public/brand/dinky/`，不参与 liangpu 构建） |
| `favicon.ico` | `dist/favicon.ico` | 同上 |
| `community/*` | `dist/community/*` | 同上 |
| `imgs/login_background.jpg`（可选） | `dist/imgs/login_background.jpg` | 覆盖 `public/imgs/login_background.jpg` |
| `icons/footer-bg.svg`（可选） | `dist/icons/footer-bg.svg` | 覆盖 `public/icons/footer-bg.svg` |

## 必选 / 可选

- **必选**（缺 → 构建 fail）：`brand-logo.svg`、`favicon.ico`
- **推荐**（缺 → 构建 warn，回退 `public/` 的中性素材）：`imgs/login_background.jpg`、`icons/footer-bg.svg`
- `community/`：企业微信 / 公众号 / 工单群二维码（无则不放）

## 注意

- 素材放这里时**天然不会被 prettier 扫描**（`.prettierignore` 已忽略整个 `/public`）。
- 本目录整体会被 umi 拷进 `dist/brand/`，随后由 `StripBrandDirPlugin`（`config/plugins/strip-brand-dir.js`）在 `afterEmit` 阶段删除，确保产物只含选中品牌的素材。
