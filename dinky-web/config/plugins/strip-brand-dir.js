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
 * 构建后剥离产物里的 `dist/brand/` 目录。
 *
 * 背景：umi 会把整个 `public/` 拷进 dist（copyPlugin.js 里 `from: join(cwd,'public')` 硬编码，无 ignore 能力），
 * 因此 `public/brand/<id>/` 里的**所有**品牌素材都会被打进产物。而其中只有选中品牌的素材
 * 需要覆盖到 dist 根，`brand/` 目录本身属于"源"，不应出现在发布包里。
 *
 * 同时做产物断言：选中品牌的 logo 必须已落到 dist 根，否则说明 copy 注入没生效。
 */
class StripBrandDirPlugin {
  constructor(options = {}) {
    this.brandDir = options.brandDir || 'brand';
    this.assetProbe = options.assetProbe || 'brand-logo.svg';
  }

  apply(compiler) {
    compiler.hooks.afterEmit.tap('StripBrandDirPlugin', () => {
      const fs = require('fs');
      const path = require('path');
      const outputPath = compiler.options.output.path;

      const brandDir = path.join(outputPath, this.brandDir);
      if (fs.existsSync(brandDir)) {
        fs.rmSync(brandDir, { recursive: true, force: true });
        console.log(`[brand] 已从产物剥离 ${this.brandDir}/（避免携带未选中品牌的素材）`);
      }

      const probe = path.join(outputPath, this.assetProbe);
      if (!fs.existsSync(probe)) {
        throw new Error(`[brand] 产物缺少品牌素材 ${this.assetProbe}，请检查 copy 注入是否生效`);
      }
    });
  }
}

module.exports = StripBrandDirPlugin;
