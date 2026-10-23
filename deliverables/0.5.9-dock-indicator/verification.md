# AutoBook 0.5.9（18）选中色块调整

状态：待验收。

选中色块以原中心向四周各扩大3dp，宽高各增加6dp；上下及首末Tab外侧到dock边缘的留白由6dp缩至约3dp。图标、标签、点击网格、dock尺寸、阴影和动画不变。

验证：111个单测全部通过；debug与release构建成功。Android15/API35模拟器已安装dist中的0.5.9 debug包，dumpsys核对versionName=0.5.9、versionCode=18，首末Tab浅深截图已核查，色块未越界。release由apksigner校验通过，签名证书SHA256与原0.5.8 release一致；两个APK由aapt核对实际版本均为0.5.9/18。release未覆盖安装到debug模拟器（签名不同，保留现有数据）。

实际安装包：dist/AutoBook-0.5.9-release.apk、dist/AutoBook-0.5.9-debug.apk。来源run：runs/20261022-093519-dock-indicator；提交版本不可取得（当前Git元信息不可用）。OpenSpec CLI不可用，已手工核对追加计划。低版本及真机触感未验证。
