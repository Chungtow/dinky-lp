# 品牌素材挂载点：liangpu

本目录是品牌 `liangpu` 的素材位置，元数据在同级 `dinky-web/brand/liangpu.json`。

> **公开仓库中本目录只有这份说明，不含任何素材。**
> 商业品牌的素材与元数据属于私有资产，存放于私有素材库，构建前由 `build.sh` 按需同步进来（见下文「素材从哪来」）。

## 目录契约

内部结构与 `public/` 逐层对应，构建期整体拷贝到产物 `dist/` 根：

| 本目录路径 | 产物路径 | 覆盖对象 |
|---|---|---|
| `brand-logo.(svg\|png)` | `dist/brand-logo.<ext>` | 无（公共层不含 logo） |
| `favicon.ico` | `dist/favicon.ico` | 无 |
| `community/*` | `dist/community/*` | 无 |
| `imgs/login_background.jpg`（可选） | `dist/imgs/login_background.jpg` | 覆盖公共层同名文件 |
| `icons/footer-bg.svg`（可选） | `dist/icons/footer-bg.svg` | 覆盖公共层同名文件 |
| `login_animation.json`（可选） | 构建期内联进登录页 chunk | 覆盖公共层动画 |

## 必选 / 可选

- **必选**（缺 → 构建 fail-fast 并列出缺失项）：`brand-logo.*`、`favicon.ico`
- **推荐**（缺 → 回退公共层中性素材）：`imgs/login_background.jpg`、`icons/footer-bg.svg`
- **可选**：`community/`（企业微信 / 公众号 / 工单群二维码等）、`login_animation.json`

## 素材从哪来

本仓库只内置官方 `dinky` 品牌。其他品牌由 `build.sh` 在构建前从私有素材库同步：

```bash
DINKY_BRAND_ASSETS=/path/to/brand-assets bash build.sh --brand liangpu
```

素材库的目录结构（`brand.json` 会被写到 `dinky-web/brand/<id>.json`，其余原样拷入本目录）：

```
brand-assets/
└─ liangpu/
   ├─ brand.json
   ├─ brand-logo.png
   ├─ favicon.ico
   ├─ login_animation.json
   ├─ community/…
   ├─ imgs/…
   └─ icons/…
```

同步进来的素材已被 `.gitignore` 忽略，不会污染工作区状态。

## 新增一个品牌

1. 准备 `dinky-web/brand/<id>.json` 元数据（字段参考 `dinky-web/brand/dinky.json`），以及 `dinky-web/public/brand/<id>/` 下的必选素材；或只在私有素材库准备好，交给 `build.sh` 同步
2. `bash build.sh --brand <id>`
3. 构建期会校验必选素材、覆盖注入产物、断言 logo 已落到 `dist/` 根，并清理中间的 `dist/brand/` 目录

不需要改动任何前端代码或配置——所有品牌走同一段逻辑，没有「官方品牌特例」分支。

## 注意

- 素材放这里时不会被 prettier 扫描（`.prettierignore` 已忽略整个 `/public`）
- 本目录整体会被 umi 拷进 `dist/brand/`，随后由 `dinky-admin` 的 `copy-static` 排除规则 + `build.sh` 清理，确保发布包只含选中品牌的素材
