# Axon EXISTS 脚本夹具

`axon-exists-new17.sql` 是 Axon 的真实 Analyzer → Generator 产物，来自
`ReplayDatabaseComparisonConfigScriptGeneratorTest#correlatedExistsGeneratesExecutableTwoSidedQueriesAndDistinctNew17Configurations`。
测试使用受控元数据，不代表已连接现场数据库。更新时重新生成并原样复制文件，不手工改写 SQL。

三个配置依次为 EXISTS 抽样（100）、NOT EXISTS 全量（-1）、EXISTS 全量（-1）；
联合业务键为 `tenant_id,item_id`。SQL 保留关联谓词及两层 canonical projection 包装。

在 Axon 仓库生成文件：

```sh
mvn -q -Dtest=ReplayDatabaseComparisonConfigScriptGeneratorTest#correlatedExistsGeneratesExecutableTwoSidedQueriesAndDistinctNew17Configurations -Daxon.exists.fixture.dir=/absolute/output test
```

在 BD 仓库使用 JDK 17 运行专项测试：

```sh
mvn -q -pl compare-service,compare-spark -Plocal-servlet-tests -Dgauss-jdbc.version=6.0.0 -Dskip.installnodenpm=true -Dskip.npm=true -Dtest=ExistsSubqueryImportTest,ExistsSubqueryComparisonTest test
```

可通过 `-Dbd.exists.fixture=/absolute/path/axon-exists-new17.sql` 指定外部生成文件，避免仓库间构建依赖。
Service 测试走生产导入器；Spark 测试在空元数据库中装载夹具，仅把 H2 不支持的事务控制交给 JDBC，查询字面值保持原样。

Spark 测试覆盖两个独立源库、复合键、内表多条命中/无匹配/NULL 状态、单侧满足条件、金额差异、
抽样与全量发布一致性，以及显式四桶读取的精确键集合。小数据生产路径自动使用一分区；
H2 的 `ORA_HASH` 验证不能代替真实 PostgreSQL/openGauss/GaussDB 方言或性能验证。
