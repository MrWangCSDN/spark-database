# BD 同表多配置实施计划与验证记录

Binding source: Axon父计划及Obsidian现行设计（2026-11-26），base 6f97c18；仅此隔离worktree。

- [x] 严格NEW17导入，三表稳定内部编号映射，保守bootstrap和历史编号保留，配置API/模型元信息。
- [x] 执行开始冻结code/source/displayName/scope；每日结果快照与Excel追加三列，原列顺序保持。
- [x] PostgreSQL/GDB增量及新建DDL、BD列表/编辑/结果展示，旧格式和构造器兼容。
- [x] 真实红绿、service/Spark/UI完整相关验证、BD受控产物联调、独立审查和提交；Axon实际产物联调交父任务。

## Decisions

- 固定配置映射采用trim+lower ROOT后的SHA-256键；原始代码保留展示。SOURCE_TABLE_NAME长度256，CONFIGURATION_NAME/CONFIGURATION_DISPLAY_NAME长度200，SCOPE为TEXT/LONGTEXT。
- 结果原CONFIGURATION_NAME/API configurationName继续表示代码；结果新增CONFIGURATION_DISPLAY_NAME/API configurationDisplayName表示人类名称。21项Spark参数不变。
- 初次映射可沿用未被当前/历史/注册表占用的包内编号；碰撞后按永久高水位分配。映射、孤儿保留编号不回收。多码同编号/同码多编号拒绝bootstrap。
- registry bootstrap、三表替换、参数恢复在同一数据库事务；行锁串行分配，ControlPlaneLock继续作为单进程保护。

## Verification log

- Baseline default Maven: 内网BES/openGauss htrunk8制品不可用；按README本地profile和gauss6.0.0验证，不修改依赖。
- Baseline UI: 109 tests passed，build passed。
- Baseline service首次230项中2个smoke因dist未构建404；已构建dist，随后复验。
- TDD import RED: 7项中6项预期失败（NEW17不支持、旧编号7被包内1替代等）；映射实现后7/7绿。
- TDD metadata RED: CRUD元数据未保存、复制复用已保留编号、结果API缺少快照字段；实现后相关专项绿。
- TDD Spark snapshot RED: SOURCE_TABLE_NAME发布为NULL；冻结上下文与发布扩展后绿。
- TDD UI RED: 3个新增可见行为测试失败；实现和测试fixture模式对齐后112/112绿，build及浏览器10项（含light/dark）通过。
- TDD long scope RED: 40000字符条件触发POI单格长度限制；添加明确截断标记，快照原文不变。
- Integration: 真实本地Spark/H2同源两配置的条件重叠，分别2行/1差异和2行/2差异；抽样/全量两路径、独立字段计数与单配置重跑隔离均通过。
- Ruling: 旧测试用改固定名称继承同编号参数与新契约冲突，改为固定code测试人类名称编辑、按code恢复参数；新配置不继承旧编号参数。
- Design reconciliation: 权威设计位于Obsidian隔离worktree dbissue-report-fields-design/obsidian（文档PR17）；旧结果三元信息保持NULL。先观察迁移测试红（实际saved_a、预期NULL），移除历史结果回填后绿。
- Independent review: 发现P1 PostgreSQL重映射BIGINT被String绑定，P2 PUT可改真实来源。两项均先独立红测试复现，再改为List<Object>/Long参数及来源等值校验；只读二次review确认闭环，无其他具体缺陷。
- Service full after review fixes: 244 tests, 0 failures, 0 errors, 1 opt-in real Gauss skip。Spark full: 102 tests, 0 failures/errors。

- Actual artifact chain: 真实HTTP导入/重排导入/完成后重导、实际本地Spark、真实HTTP导出Excel，产物位于当前Codex可视化目录的bd-multi-config。输入为BD受控fixture，未冒称Axon生成器产物。
- Cross-project follow-up: Axon真实生成脚本和问题清单回导联调由父任务协调；此仓库提供可用实际Excel、结果JSON和导入响应。

- Final verify: JDK17 `mvn -Plocal-servlet-tests -Dgauss-jdbc.version=6.0.0 -Dskip.installnodenpm=true -Dskip.npm=true verify` exit0；service244(1 skipped), Spark102，全0 failures/errors；UI112、e2e10、production build通过。默认BES/openGauss htrunk8内网制品不在本地仓库，未宣称厂商发布profile或真实厂商DB已验证。
