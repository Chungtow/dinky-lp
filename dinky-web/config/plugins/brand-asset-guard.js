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

/**
 * 构建后校验「品牌素材覆盖注入」是否生效。
 *
 * 背景：umi 会把整个 `public/` 拷进 dist（`copyPlugin.js` 里 `from: join(cwd,'public')` 硬编码，
 * 无 ignore 能力），因此 `public/brand/<id>/` 会以 `dist/brand/<id>/` 的形式出现在中间产物里；
 * 而选中品牌的素材是通过 `config.ts` 的 `copy` 覆盖注入到 **dist 根**的。
 *
 * 卫生由打包层负责：`dinky-admin/pom.xml` 的 `copy-static` 用 `<exclude>brand/**</exclude>`
 * 排除品牌源目录，所以发布包不会携带它；`build.sh` 在构建结束后清理 `dist/brand`。
 *
 * 本插件只做 fail-fast 校验：logo 必须已落到 dist 根，否则说明 copy 注入没生效
 * （例如品牌目录路径写错、`to` 少了 `dist`），此时产物会缺少品牌素材。
 */
class BrandAssetGuardPlugin {
  constructor(options = {}) {
    this.assetProbe = options.assetProbe || 'brand-logo.svg';
  }

  apply(compiler) {
    compiler.hooks.afterEmit.tap('BrandAssetGuardPlugin', () => {
      const fs = require('fs');
      const path = require('path');
      const probe = path.join(compiler.options.output.path, this.assetProbe);
      if (!fs.existsSync(probe)) {
        throw new Error(`[brand] 产物缺少品牌素材 ${this.assetProbe}，请检查 copy 注入是否生效`);
      }
      console.log(`[brand] 品牌素材已注入产物根：${this.assetProbe}`);
    });
  }
}

module.exports = BrandAssetGuardPlugin;
