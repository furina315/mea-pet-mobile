# Third-Party Notices

This project includes third-party components distributed under
their respective licenses. These components are NOT covered by
the MIT license of this project.

---

## Live2D Cubism Framework (Java)

- **Location in this repo:** `app/src/main/java/com/live2d/`
- **Copyright:** Copyright(c) Live2D Inc. All rights reserved.
- **License:** Live2D Open Software License Agreement
- **License text:** `app/src/main/java/com/live2d/LICENSE.md`
- **URL:** https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html

---

## Live2D Cubism Framework Shader Resources

- **Location in this repo:** `app/src/main/assets/com/live2d/`
  (GLSL shader sources under `sdk/cubism/framework/shaders/`)
- **Copyright:** Copyright(c) Live2D Inc. All rights reserved.
- **License:** Live2D Open Software License Agreement
- **License text:** `app/src/main/assets/com/live2d/LICENSE.md`
- **URL:** https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html
- **Notes:** These shader assets ship with the Cubism Java Framework,
  which is part of Live2D Cubism Components and is available under the
  Open Software License (same as the Framework code above). They are
  **not** the sample character models listed under Live2D's Free
  Material License. Only **Cubism Core** is under the Proprietary
  Software License — see the next section.

---

## Live2D Cubism Core (NOT included)

Live2D Cubism Core is **not distributed** in this repository.
It must be downloaded separately from the official Live2D website:

  https://www.live2d.com/download/cubism-sdk/download-java/

Cubism Core is subject to the **Live2D Proprietary Software License
Agreement**, which you must accept directly with Live2D Inc.
when downloading.

  https://www.live2d.com/eula/live2d-proprietary-software-license-agreement_en.html

---

## Mea (梅尔) Live2D Character Model

The character Live2D model shipped / used by this app is a community work
and is **not** licensed under this project's MIT license.

- **Source:** Bilibili — [Live2D模型分享 - 梅娅]
- **URL:** https://www.bilibili.com/video/BV1AoX7BXEaN
- **Notes:** Copyright remains with the original author(s). Follow the
  terms stated in the original release when redistributing or reusing
  the model assets.

---

## Material Design Icons (Pictogrammers)

Interface icons converted to `VectorDrawable`.

- **Location in this repo:** `app/src/main/res/drawable/ic_*.xml`
- **Copyright:** Copyright (c) Pictogrammers
- **License:** Apache License 2.0
- **URL:** https://pictogrammers.com/library/mdi/
- **License text:** https://www.apache.org/licenses/LICENSE-2.0
- **Notes:** 21 icons converted from Iconify SVG via `tools/svg2vd.py`
  (see `tools/icons.txt`). Apache 2.0 does not require attribution, but
  credit is given on the in-app About page as good practice.

---

## Umeng+ (友盟+) U-APP Statistics SDK (NOT included)

The Umeng+ analytics SDK is integrated as a Gradle dependency
(`com.umeng.umsdk:common` + `com.umeng.umsdk:asms`, see
`gradle/libs.versions.toml`); it is **not distributed** in this
repository and is fetched at build time.

- **Provider:** 友盟同欣（北京）科技有限公司 / Umeng (an Alibaba company)
- **License:** Proprietary — subject to the Umeng service terms and
  privacy policy, which the app developer accepts when integrating.
- **URL:** https://www.umeng.com/
- **Privacy policy:** https://www.umeng.com/page/policy
- **Notes:** Used only for de-identified usage statistics, and only after
  the user grants consent (first-launch dialog; revocable in Settings →
  About → 隐私与数据). The AppKey is injected via `local.properties`
  (`umeng.appKey`), not hard-coded; forks should supply their own key or
  remove the dependency.
