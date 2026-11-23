## 改了什么

<!-- 一句话说明动机与结果；关联 issue 请写 Closes #xxx。 -->

## 影响面

- [ ] 协议 / 上游请求（需同步 docs/protocol-compat.md）
- [ ] 存储 / 导入 / 索引格式（需考虑旧数据兼容）
- [ ] 计费 / 语音 / 账本
- [ ] UI / 主题 / 文案
- [ ] 构建 / 签名 / 资源
- [ ] 仅文档

## 验证

- [ ] gradle :app:compileDebugKotlin
- [ ] gradle :app:testDebugUnitTest
- [ ] 本地 mock 联调（python3 scripts/mock_sse_server.py --port 18080 + -PapiOrigin）
- [ ] 真机验证（机型 / Android 版本：____）

关键输出（不要贴 Key 或会话内容）：

## 风险与回滚

<!-- 已知风险、兼容性影响、如何回滚。 -->

## 检查清单

- [ ] 未提交构建产物、签名材料、local.properties、用户数据
- [ ] 未新增第三方依赖（或已在描述中说明理由）
- [ ] 能力/限制变化已更新 README.md，架构变化已更新 ARCHITECTURE.md
- [ ] UI 截图不含 API Key、会话正文与个人数据
