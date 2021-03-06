package org.apache.spark.sql.execution.datasources

import org.apache.spark.Logging
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.analysis.{UnresolvedAlias, UnresolvedAttribute, UnresolvedRelation, UnresolvedStar}
import org.apache.spark.sql.catalyst.expressions.Literal
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.sources.commands._
import org.apache.spark.sql.sources.sql.{Dimension, Plain, Cube => CubeKind}
import org.apache.spark.sql.types._
import org.apache.spark.sql.{SapParserDialect, SapParserException}
import org.apache.spark.util.AnnotationParsingUtils
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.{FunSuite, GivenWhenThen}

// scalastyle: off file.size.limit
class SapDDLParserSuite
  extends FunSuite
  with TableDrivenPropertyChecks
  with GivenWhenThen
  with AnnotationParsingUtils
  with Logging {

  val sqlParser = new SapParserDialect
  val ddlParser = new SapDDLParser(sqlParser.parse)

  test("DEEP DESCRIBE command") {
    val parsed = ddlParser.parse("DEEP DESCRIBE test")
    assert(parsed == UnresolvedDeepDescribe(UnresolvedRelation(TableIdentifier("test"))))
  }

  test("SHOW PARTITION FUNCTIONS command") {
    val parsed1 = ddlParser.parse("SHOW PARTITION FUNCTIONS USING com.sap.spark.dstest")
    val parsed2 = ddlParser.parse("SHOW PARTITION FUNCTIONS USING com.sap.spark.dstest " +
      "OPTIONS (foo \"bar\")")
    assertResult(
      ShowPartitionFunctionsUsingCommand("com.sap.spark.dstest", Map.empty))(parsed1)
    assertResult(
      ShowPartitionFunctionsUsingCommand("com.sap.spark.dstest", Map("foo" -> "bar")))(parsed2)
  }

  test("CREATE TABLE keeps the ddl statement in the options") {
    val ddl =
      s"""CREATE TABLE foo (a int, b int)
         |USING com.sap.spark.vora
         |OPTIONS (
         |)""".stripMargin

    val parsed = ddlParser.parse(ddl)

    assertResult(ddl)(parsed.asInstanceOf[CreateTableUsing].options("table_ddl"))
  }

  test("CREATE TABLE does not override a user provided ddl statement") {
    val ddl =
      s"""CREATE TABLE foo (a int, b int)
          |USING com.sap.spark.vora
          |OPTIONS (
          |table_ddl "bar"
          |)""".stripMargin

    val parsed = ddlParser.parse(ddl)

    assertResult("bar")(parsed.asInstanceOf[CreateTableUsing].options("table_ddl"))
  }

  test("OPTIONS (CONTENT) command") {
    val optionsPermutations = Table(
      """(
        |a "a",
        |b "b",
        |C "c"
        |)
      """.stripMargin,
      """(
        |A "a",
        |B "b",
        |c "c"
        |)
      """.stripMargin
    )

    forAll(optionsPermutations) { (opts) =>
      val statement = s"SHOW DATASOURCETABLES USING com.provider OPTIONS $opts"
      Given(s"statement $statement")

      val parsed = ddlParser.parse(statement).asInstanceOf[ShowDatasourceTablesCommand]
      val options = parsed.options

      Then("The resulting options map will have lower-cased keys")
      assert(options == Map(
        "a" -> "a",
        "b" -> "b",
        "c" -> "c"
      ))
    }
  }

  val showDatasourceTablesPermutations = Table(
    ("sql", "provider", "options", "willFail"),
    ("SHOW DATASOURCETABLES USING com.provider", "com.provider", Map.empty[String, String], false),
    ("SHOW DATASOURCETABLES USING com.provider OPTIONS(key \"value\")",
      "com.provider", Map("key" -> "value"), false),
    ("SHOW DATASOURCETABLES", "", Map.empty[String, String], true)
  )

  test("SHOW DATASOURCETABLES command") {
    forAll(showDatasourceTablesPermutations) { (sql, provider, options, willFail) =>

      Given(s"provider: $provider, options: $options, sql: $sql, willFail: $willFail")

      if (willFail) {
        intercept[SapParserException] {
          ddlParser.parse(sql)
        }
      } else {
        val result = ddlParser.parse(sql)

        Then("it will be an instance of ShowDatasourceTablesCommand class")
        assert(result.isInstanceOf[ShowDatasourceTablesCommand])

        val instancedResult = result.asInstanceOf[ShowDatasourceTablesCommand]

        Then("options will be equals")
        assert(instancedResult.options == options)
        Then("provider will be equals")
        assert(instancedResult.classIdentifier == provider)
      }
    }
  }

  val registerAllTablesCommandPermutations =
    Table(
      ("sql", "provider", "options", "ignoreConflicts"),
      ("REGISTER ALL TABLES USING provider.name OPTIONS() IGNORING CONFLICTS",
        "provider.name", Map.empty[String, String], true),
      ("""REGISTER ALL TABLES USING provider.name OPTIONS(optionA "option")""",
        "provider.name", Map("optiona" -> "option"), false),
      ("""REGISTER ALL TABLES USING provider.name""",
        "provider.name", Map.empty[String, String], false),
      ("""REGISTER ALL TABLES USING provider.name IGNORING CONFLICTS""",
        "provider.name", Map.empty[String, String], true)
    )

  test("REGISTER ALL TABLES command") {
    forAll(registerAllTablesCommandPermutations) {
      (sql: String, provider: String, options: Map[String, String], ignoreConflicts: Boolean) =>
        Given(s"provider: $provider, options: $options, ignoreConflicts: $ignoreConflicts")
        val result = ddlParser.parse(sql)

        Then("the result will be a instance of RegisterAllTablesUsing")
        assert(result.isInstanceOf[RegisterAllTablesUsing])

        val convertedResult = result.asInstanceOf[RegisterAllTablesUsing]

        Then("the ignoreConflicts will be correct")
        assert(convertedResult.ignoreConflicts == ignoreConflicts)
        Then("the options will be correct")
        assert(convertedResult.options == options)
        Then("the provider name will be correct")
        assert(convertedResult.provider == provider)
    }
  }

  val registerTableCommandPermutations =
    Table(
      ("sql", "table", "provider", "options", "ignoreConflicts"),
      ("REGISTER TABLE bla USING provider.name OPTIONS() IGNORING CONFLICTS",
        "bla", "provider.name", Map.empty[String, String], true),
      ("""REGISTER TABLE bla USING provider.name OPTIONS(optionA "option")""",
        "bla", "provider.name", Map("optiona" -> "option"), false),
      ("""REGISTER TABLE bla USING provider.name""",
        "bla", "provider.name", Map.empty[String, String], false),
      ("""REGISTER TABLE bla USING provider.name IGNORING CONFLICTS""",
        "bla", "provider.name", Map.empty[String, String], true)
    )

  test("REGISTER TABLE command") {
    forAll(registerTableCommandPermutations) {
      (sql: String, table: String, provider: String, options: Map[String, String],
       ignoreConflict: Boolean) =>
        Given(s"provider: $provider, options: $options, ignoreConflicts: $ignoreConflict")
        val result = ddlParser.parse(sql)

        Then("the result will be a instance of RegisterAllTablesUsing")
        assert(result.isInstanceOf[RegisterTableUsing])

        val convertedResult = result.asInstanceOf[RegisterTableUsing]

        Then("the table name is correct")
        assert(convertedResult.tableName == table)
        Then("the ignoreConflicts will be correct")
        assert(convertedResult.ignoreConflict == ignoreConflict)
        Then("the options will be correct")
        assert(convertedResult.options == options)
        Then("the provider name will be correct")
        assert(convertedResult.provider == provider)
    }
  }

  test("test DDL of Bug 90774") {
    val testTable = """
CREATE TEMPORARY TABLE testBaldat (field1 string, field2 string, field3 string,
  field4 string, field5 integer, field6 string, field7 integer)
USING com.sap.spark.vora
OPTIONS (
  tableName "testBaldat",
  files "/user/u1234/data.csv"
)"""
    ddlParser.parse(testTable, exceptionOnError = true)
    ddlParser.parse(testTable, exceptionOnError = false)
  }

  test("Replace 'paths' for vora datasource with files if needed (deprecation)") {
    val testTableVora = """
      CREATE TABLE testDeprec (field1 string)
      USING com.sap.spark.vora
      OPTIONS (
        tableName "testDep",
        paths "/user/u1234/data.csv"
    )"""
    assert(ddlParser.parse(testTableVora, exceptionOnError = true)
      .asInstanceOf[CreateTableUsing].options.contains("files"))
    assert(!ddlParser.parse(testTableVora, exceptionOnError = true)
      .asInstanceOf[CreateTableUsing].options.contains("paths"))

    val testTableVoraDS = """
      CREATE TABLE testDeprec (field1 string)
      USING com.sap.spark.vora.DefaultSource
      OPTIONS (
        tableName "testDep",
        paths "/user/u1234/data.csv"
    )"""
    assert(ddlParser.parse(testTableVoraDS, exceptionOnError = true)
      .asInstanceOf[CreateTableUsing].options.contains("files"))
    assert(!ddlParser.parse(testTableVoraDS, exceptionOnError = true)
      .asInstanceOf[CreateTableUsing].options.contains("paths"))
  }

  test("Replace 'path' for hana datasource with files if needed (deprecation)") {
    val testTableHana = """
      CREATE TABLE testDeprec (field1 string)
      USING com.sap.spark.hana
      OPTIONS (
        tableName "testDep",
        path "NAME"
    )"""
    assert(ddlParser.parse(testTableHana, exceptionOnError = true)
      .asInstanceOf[CreateTableUsing].options.contains("tablepath"))
    assert(!ddlParser.parse(testTableHana, exceptionOnError = true)
      .asInstanceOf[CreateTableUsing].options.contains("path"))

    val testTableHanaDS = """
      CREATE TABLE testDeprec (field1 string)
      USING com.sap.spark.hana.DefaultSource
      OPTIONS (
        tableName "testDep",
        path "/user/u1234/data.csv"
    )"""
    assert(ddlParser.parse(testTableHanaDS, exceptionOnError = true)
      .asInstanceOf[CreateTableUsing].options.contains("tablepath"))
    assert(!ddlParser.parse(testTableHanaDS, exceptionOnError = true)
      .asInstanceOf[CreateTableUsing].options.contains("path"))
  }

  test("test simple CREATE TEMPORARY TABLE (Bug 90774)") {
    val testTable = """CREATE TEMPORARY TABLE tab001(a int)
      USING a.b.c.d
      OPTIONS (
        tableName "blaaa"
      )"""
    ddlParser.parse(testTable, exceptionOnError = true)
    ddlParser.parse(testTable, exceptionOnError = false)
  }

  /* Checks that the parse error position
   * corresponds to the syntax error position.
   *
   * Since the ddlParser falls back to the sqlParser
   * on error throwing the correct parse exception
   * is crucial. I.e., this test makes sure that not only
   * exception from the sqlParser is thrown on failure
   * but the one from the parser that consumed the most characters.
   */
  test("check reasonable parse errors") {

    val wrongSyntaxQueries = Array(
      ("CREATE TEMPORARY TABLE table001 (a1 int_, a2 int)", 1, 37),
      ("CREATE VIEW bla AZZ SELECT * FROM foo", 1, 17),
      ("""CREATE TEMPORARY TABL____ table001 (a1 int, a2 int)
USING com.sap.spark.vora
OPTIONS (
  tableName "table001")""", 1, 18),
      ("""CREATE TEMPORARY TABLE table001 (a1 int, a2 int)
USIN_ com.sap.spark.vora
OPTIONS (
  tableName "table001")""", 2, 1),
      ("""CREATE TEMPORARY TABLE tab01(a int)
USING com.sap.spark.vora
OPTIONS (
  tableName "table001" """, 4, 24),
      ("SELCT * FROM table001", 1, 1),
      ("CREAT TABLE table001(a1 int)", 1, 1),
      ("", 1, 1),
      ("   ", 1, 4),
      ("\n\n\n\n", 5, 1),
      ("abcdefg", 1, 1)
    )

    for((query, line, col) <- wrongSyntaxQueries) {
      val vpe: SapParserException = intercept[SapParserException] {
        ddlParser.parse(query, exceptionOnError = false)
      }
      val expLine = vpe.line
      val expCol = vpe.column
      assert(expLine == line)
      assert(expCol == col)
    }
  }

  test("Parse any USE xyz statements") {
    // Every valid "USE xyz" statement should produce a
    // UseStatementCommand.
    assert(ddlParser.parse("USE abc").isInstanceOf[UseStatementCommand])
    assert(ddlParser.parse("USE abc abc").isInstanceOf[UseStatementCommand])
    assert(ddlParser.parse("use abc abc").isInstanceOf[UseStatementCommand])
    assert(ddlParser.parse("USE ..... ...").isInstanceOf[UseStatementCommand])
    assert(ddlParser.parse("USE abc").isInstanceOf[UseStatementCommand])
    assert(ddlParser.parse("USE").isInstanceOf[UseStatementCommand])
    intercept[SapParserException] {
      ddlParser.parse("CREATE TABLE use (a int) using x.y.z")
    }
    intercept[SapParserException] {
      ddlParser.parse("USER")
    }
    intercept[SapParserException] {
      ddlParser.parse("USING")
    }
  }

  test("Parse correct CREATE TABLE statements with the PARTITION BY clause") {
    val testStatement1 = """CREATE TEMPORARY TABLE test1 (a integer, b string)
                        PARTITIONED BY example (a)
                        USING com.sap.spark.vora
                        OPTIONS (
                        tableName "test1",
                        files "/data.csv")"""
    val parsedStmt1 = ddlParser.parse(testStatement1)
    assert(parsedStmt1.isInstanceOf[CreateTablePartitionedByUsing])

    val ctp1 = parsedStmt1.asInstanceOf[CreateTablePartitionedByUsing]
    assert(ctp1.tableIdent.table == "test1")
    assert(ctp1.userSpecifiedSchema.isDefined)
    assert(ctp1.userSpecifiedSchema.get ==
      StructType(Seq(StructField("a", IntegerType, nullable = true),
        StructField("b", StringType, nullable = true))))
    assert(ctp1.partitioningFunc == "example")
    assert(ctp1.partitioningColumns == Seq("a"))
    assert(ctp1.provider == "com.sap.spark.vora")

    val testStatement2 = """CREATE TEMPORARY TABLE test1 (a integer, b string)
                        PARTITIONED BY example (a, b)
                        USING com.sap.spark.vora
                        OPTIONS (
                        tableName "test1",
                        files "/data.csv")"""
    val parsedStmt2 = ddlParser.parse(testStatement2)
    assert(parsedStmt2.isInstanceOf[CreateTablePartitionedByUsing])

    val ctp2 = parsedStmt2.asInstanceOf[CreateTablePartitionedByUsing]
    assert(ctp2.tableIdent.table == "test1")
    assert(ctp2.userSpecifiedSchema.isDefined)
    assert(ctp2.userSpecifiedSchema.get ==
      StructType(Seq(StructField("a", IntegerType, nullable = true),
        StructField("b", StringType, nullable = true))))
    assert(ctp2.partitioningFunc == "example")
    assert(ctp2.partitioningColumns == Seq("a", "b"))
    assert(ctp2.provider == "com.sap.spark.vora")


    val testStatement3 = """CREATE TEMPORARY TABLE test1 (a integer, b string, test float)
                        PARTITIONED BY example (test)
                        USING com.sap.spark.vora
                        OPTIONS (
                        tableName "test1",
                        files "/data.csv")"""
    val parsedStmt3 = ddlParser.parse(testStatement3)
    assert(parsedStmt3.isInstanceOf[CreateTablePartitionedByUsing])

    val ctp3 = parsedStmt3.asInstanceOf[CreateTablePartitionedByUsing]
    assert(ctp3.tableIdent.table == "test1")
    assert(ctp3.userSpecifiedSchema.isDefined)
    assert(ctp3.userSpecifiedSchema.get ==
      StructType(Seq(StructField("a", IntegerType, nullable = true),
        StructField("b", StringType, nullable = true),
        StructField("test", FloatType, nullable = true))))
    assert(ctp3.partitioningFunc == "example")
    assert(ctp3.partitioningColumns == Seq("test"))
    assert(ctp3.provider == "com.sap.spark.vora")
  }

  test("Do not parse incorrect CREATE TABLE statements with the PARTITION BY clause") {
    val invStatement = """CREATE TEMPORARY TABLE test1 (a integer, b string)
                       PARTITIONED BY example
                       USING com.sap.spark.vora
                       OPTIONS (
                       tableName "test1",
                       files "/data.csv")"""
    intercept[SapParserException](ddlParser.parse(invStatement))
  }

  test("Parse a correct CREATE PARTITION FUNCTION HASH statement without the PARTITIONS clause") {
    val testTable =
      """CREATE PARTITION FUNCTION test (integer, string) AS HASH
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    val parsedStmt = ddlParser.parse(testTable)
    assert(ddlParser.parse(testTable).isInstanceOf[CreateHashPartitioningFunction])

    val cpf = parsedStmt.asInstanceOf[CreateHashPartitioningFunction]
    assert(cpf.parameters.contains("discovery"))
    assert(cpf.parameters("discovery") == "1.1.1.1")
    assert(cpf.name == "test")
    assert(cpf.datatypes == Seq(IntegerType, StringType))
    assert(cpf.provider == "com.sap.spark.vora")
  }

  test("Parse a correct CREATE PARTITION FUNCTION HASH statement with the PARTITIONS clause") {
    val testTable =
      """CREATE PARTITION FUNCTION test (integer, string) AS HASH PARTITIONS 7
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    val parsedStmt = ddlParser.parse(testTable)
    assert(ddlParser.parse(testTable).isInstanceOf[CreateHashPartitioningFunction])

    val cpf = parsedStmt.asInstanceOf[CreateHashPartitioningFunction]
    assert(cpf.parameters.contains("discovery"))
    assert(cpf.parameters("discovery") == "1.1.1.1")
    assert(cpf.name == "test")
    assert(cpf.datatypes == Seq(IntegerType, StringType))
    assert(cpf.partitionsNo.isDefined)
    assert(cpf.partitionsNo.get == 7)
    assert(cpf.provider == "com.sap.spark.vora")
  }

  // scalastyle:off magic.number
  test("Parse a correct CREATE PARTITION FUNCTION RANGE statement with SPLITTERS") {
    val testTable1 =
      """CREATE PARTITION FUNCTION test (integer) AS RANGE SPLITTERS (5, 10, 15)
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    val parsedStmt1 = ddlParser.parse(testTable1)
    assert(ddlParser.parse(testTable1).isInstanceOf[CreateRangeSplittersPartitioningFunction])

    val cpf1 = parsedStmt1.asInstanceOf[CreateRangeSplittersPartitioningFunction]
    assert(cpf1.parameters.contains("discovery"))
    assert(cpf1.parameters("discovery") == "1.1.1.1")
    assert(cpf1.name == "test")
    assert(cpf1.datatype == IntegerType)
    assert(!cpf1.rightClosed)
    assert(cpf1.splitters == Seq(5, 10, 15))
    assert(cpf1.provider == "com.sap.spark.vora")

    val testTable2 =
      """CREATE PARTITION FUNCTION test (integer) AS RANGE SPLITTERS RIGHT CLOSED (5, 20)
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    val parsedStmt2 = ddlParser.parse(testTable2)
    assert(ddlParser.parse(testTable2).isInstanceOf[CreateRangeSplittersPartitioningFunction])

    val cpf2 = parsedStmt2.asInstanceOf[CreateRangeSplittersPartitioningFunction]
    assert(cpf2.parameters.contains("discovery"))
    assert(cpf2.parameters("discovery") == "1.1.1.1")
    assert(cpf2.name == "test")
    assert(cpf2.datatype == IntegerType)
    assert(cpf2.rightClosed)
    assert(cpf2.splitters == Seq(5, 20))
    assert(cpf2.provider == "com.sap.spark.vora")
  }

  test("Parse a correct CREATE PARTITION FUNCTION RANGE statement with START/END") {
    val testTable1 =
      """CREATE PARTITION FUNCTION test (integer) AS RANGE START 5 END 20 STRIDE 2
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    val parsedStmt1 = ddlParser.parse(testTable1)
    assert(ddlParser.parse(testTable1).isInstanceOf[CreateRangeIntervalPartitioningFunction])

    val cpf1 = parsedStmt1.asInstanceOf[CreateRangeIntervalPartitioningFunction]
    assert(cpf1.parameters.contains("discovery"))
    assert(cpf1.parameters("discovery") == "1.1.1.1")
    assert(cpf1.name == "test")
    assert(cpf1.datatype == IntegerType)
    assert(cpf1.start == 5)
    assert(cpf1.end == 20)
    assert(cpf1.strideParts == Left(2))
    assert(cpf1.provider == "com.sap.spark.vora")

    val testTable2 =
      """CREATE PARTITION FUNCTION test (integer) AS RANGE START 5 END 25 PARTS 3
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    val parsedStmt2 = ddlParser.parse(testTable2)
    assert(ddlParser.parse(testTable2).isInstanceOf[CreateRangeIntervalPartitioningFunction])

    val cpf2 = parsedStmt2.asInstanceOf[CreateRangeIntervalPartitioningFunction]
    assert(cpf2.parameters.contains("discovery"))
    assert(cpf2.parameters("discovery") == "1.1.1.1")
    assert(cpf2.name == "test")
    assert(cpf2.datatype == IntegerType)
    assert(cpf2.start == 5)
    assert(cpf2.end == 25)
    assert(cpf2.strideParts == Right(3))
    assert(cpf2.provider == "com.sap.spark.vora")
  }
  // scalastyle:on magic.number

  test("Do not parse incorrect CREATE PARTITION FUNCTION statements") {
    val invStatement1 =
      """CREATE PARTITION FUNCTION (integer, string) AS HASH PARTITIONS 7
        |USING com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement1))

    val invStatement2 =
      """CREATE PARTITION FUNCTION 44test (integer,) AS HASH PARTITIONS 7
        |USING com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement2))

    val invStatement3 =
      """CREATE PARTITION FUNCTION test AS HASH
        |USING com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement3))

    val invStatement4 =
      """CREATE PARTITION FUNCTION test AS HASH PARTITIONS 7
        |USING com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement4))

    val invStatement5 =
      """CREATE PARTITION FUNCTION test (integer, string) HASH PARTITIONS 7
        |USING com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement5))

    val invStatement6 =
      """CREATE PARTITION FUNCTION test (integer, string) AS HASH
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement6))

    val invStatement7 =
      """CREATE PARTITION FUNCTION test (integer, string) AS HASH
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement7))

    val invStatement8 =
      """CREATE PARTITION FUNCION test (integer, string) AS HASH
        |USING com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement8))

    val invStatement9 =
      """CREATE PARTITION FUNCTION test () AS HASH
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    val ex1 = intercept[DDLException](ddlParser.parse(invStatement9))
    assert(ex1.getMessage.contains("The hashing function argument list cannot be empty."))

    val invStatement10 =
      """CREATE PARTITION FUNCTION test AS RANGE SPLITTERS ("5", "10", "15")
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement10))

    val invStatement11 =
      """CREATE PARTITION FUNCTION test () AS RANGE SPLITTERS ()
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    val ex4 = intercept[DDLException](ddlParser.parse(invStatement11))
    assert(ex4.getMessage.contains("The range function argument list cannot be empty."))

    val invStatement12 =
      """CREATE PARTITION FUNCTION test (integer, string) AS RANGE SPLITTERS (5, 10, 15)
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    val ex5 = intercept[DDLException](ddlParser.parse(invStatement12))
    assert(ex5.getMessage.contains("The range functions cannot have more than one argument."))

    val invStatement13 =
      """CREATE PARTITION FUNCTION test (integer, string) AS RANGE SPLIYTTERS (5, 10, 15)
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement13))

    val invStatement14 =
      """CREATE PARTITION FUNCTION test AS RANGE START 5 END 10 STRIDE 1
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement14))

    val invStatement15 =
      """CREATE PARTITION FUNCTION test () AS RANGE START 5 END 10 STRIDE 1
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    val ex6 = intercept[DDLException](ddlParser.parse(invStatement15))
    assert(ex6.getMessage.contains("The range function argument list cannot be empty."))

    val invStatement16 =
      """CREATE PARTITION FUNCTION test (integer, string) AS RANGE START 5 END 10 STRIDE 1
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    val ex7 = intercept[DDLException](ddlParser.parse(invStatement16))
    assert(ex7.getMessage.contains("The range functions cannot have more than one argument."))

    val invStatement17 =
      """CREATE PARTITION FUNCTION test (integer, string) AS RANGE START 5 END 10 STRdIDE 1
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement17))

    val invStatement18 =
      """CREATE PARTITION FUNCTION test (integer, string) AS RANGE START END 10 STRIDE 1
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement18))

    val invStatement19 =
      """CREATE PARTITION FUNCTION test (integer, string) AS RANGE START "DF" END STRIDE 1
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement19))

    val invStatement20 =
      """CREATE PARTITION FUNCTION test (integer, string) AS RANGE START "DF" END "ZZ"
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement20))
  }

  test("Parse a correct DROP PARTITION FUNCTION statement") {
    val testTable =
      """DROP PARTITION FUNCTION test
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "local")
      """.stripMargin
    val parsedStmt = ddlParser.parse(testTable)
    assert(ddlParser.parse(testTable).isInstanceOf[DropPartitioningFunction])

    val dpf = parsedStmt.asInstanceOf[DropPartitioningFunction]
    assert(dpf.parameters.contains("discovery"))
    assert(dpf.parameters("discovery") == "local")
    assert(dpf.name == "test")
    assert(!dpf.allowNotExisting)
    assert(dpf.provider == "com.sap.spark.vora")
  }

  test("Parse a correct DROP PARTITION FUNCTION IF EXISTS statement") {
    val testTable =
      """DROP PARTITION FUNCTION IF EXISTS test
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "local")
      """.stripMargin
    val parsedStmt = ddlParser.parse(testTable)
    assert(ddlParser.parse(testTable).isInstanceOf[DropPartitioningFunction])

    val dpf = parsedStmt.asInstanceOf[DropPartitioningFunction]
    assert(dpf.parameters.contains("discovery"))
    assert(dpf.parameters("discovery") == "local")
    assert(dpf.name == "test")
    assert(dpf.allowNotExisting)
    assert(dpf.provider == "com.sap.spark.vora")
  }

  test("Do not parse incorrect DROP PARTITION FUNCTION statements") {
    val invStatement1 =
      """DROP FUNCTION test
        |USING com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement1))

    val invStatement2 =
      """DROP PARTITION FUNCTION
        |USING com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement2))

    val invStatement3 =
      """DROP PARTITION FUNCTION test
        |USG com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement3))

    val invStatement4 =
      """DROP PARTITION FUNCTION test
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement4))
  }

  test("Parse correct CREATE VIEW USING") {
    val statement = "CREATE VIEW v AS SELECT * FROM t USING com.sap.spark.vora"

    val parsed = ddlParser.parse(statement)
    assert(parsed.isInstanceOf[CreatePersistentViewCommand])

    val actual = parsed.asInstanceOf[CreatePersistentViewCommand]
    assert(actual.kind == Plain)
    assertResult(statement)(actual.viewSql)
    assertResult(Project(UnresolvedAlias(UnresolvedStar(None)) :: Nil,
      UnresolvedRelation(TableIdentifier("t"))))(actual.plan)
    assertResult(false)(actual.allowExisting)
    assertResult(TableIdentifier("v"))(actual.identifier)
    assertResult("com.sap.spark.vora")(actual.provider)
    assertResult(Map.empty)(actual.options)
  }

  test("Parse correct CREATE VIEW USING with sub-select (bug 105558") {
    val statement = "CREATE VIEW v AS SELECT sq.a FROM " +
      "(SELECT * FROM t) sq USING com.sap.spark.vora"

    val parsed = ddlParser.parse(statement)
    assert(parsed.isInstanceOf[CreatePersistentViewCommand])

    val actual = parsed.asInstanceOf[CreatePersistentViewCommand]
    assert(actual.kind == Plain)

    assertResult(
      Project(
        UnresolvedAlias(UnresolvedAttribute(Seq("sq", "a"))) :: Nil,
        Subquery(
          "sq",
          Project(
            UnresolvedAlias(UnresolvedStar(None)) :: Nil,
            UnresolvedRelation(TableIdentifier("t"), None)
          )
        )
    ))(actual.plan)
    assertResult(statement)(actual.viewSql)
    assertResult(false)(actual.allowExisting)
    assertResult(TableIdentifier("v"))(actual.identifier)
    assertResult("com.sap.spark.vora")(actual.provider)
    assertResult(Map.empty)(actual.options)
  }

  test("Parse correct CREATE VIEW USING OPTIONS") {
    val statement = """CREATE VIEW IF NOT EXISTS v
                   |AS SELECT * FROM t
                   |USING com.sap.spark.vora
                   |OPTIONS(discovery "1.1.1.1")""".stripMargin

    val parsed = ddlParser.parse(statement)
    assert(parsed.isInstanceOf[CreatePersistentViewCommand])

    val actual = parsed.asInstanceOf[CreatePersistentViewCommand]
    assert(actual.kind == Plain)
    assertResult(Project(UnresolvedAlias(UnresolvedStar(None)) :: Nil,
      UnresolvedRelation(TableIdentifier("t"))))(actual.plan)
    assertResult(true)(actual.allowExisting)
    assertResult(statement)(actual.viewSql)
    assertResult(TableIdentifier("v"))(actual.identifier)
    assertResult("com.sap.spark.vora")(actual.provider)
    assertResult(Map[String, String]("discovery" -> "1.1.1.1"))(actual.options)
  }

  test("Parse correct CREATE VIEW USING with annotations") {
    val statement = """CREATE VIEW IF NOT EXISTS v
                      |AS SELECT a as al @ ( b = 'c' ) FROM t
                      |USING com.sap.spark.vora
                      |OPTIONS(discovery "1.1.1.1")""".stripMargin

    val parsed = ddlParser.parse(statement)
    assert(parsed.isInstanceOf[CreatePersistentViewCommand])
    val persistedViewCommand = parsed.asInstanceOf[CreatePersistentViewCommand]
    assertResult(persistedViewCommand.identifier.table)("v")
    assertResult(statement)(persistedViewCommand.viewSql)
    assert(persistedViewCommand.kind == Plain)
    assert(persistedViewCommand.plan.isInstanceOf[Project])
    val projection = persistedViewCommand.plan.asInstanceOf[Project]

    assertResult(UnresolvedRelation(TableIdentifier("t")))(projection.child)

    val expected = Seq(
      ("al", UnresolvedAttribute("a"), Map("b" -> Literal.create("c", StringType))))

    assertAnnotatedProjection(expected)(projection.projectList)

    assertResult("com.sap.spark.vora")(persistedViewCommand.provider)
    assertResult(Map[String, String]("discovery" -> "1.1.1.1"))(persistedViewCommand.options)
  }

  test("Handle incorrect CREATE VIEW statements") {
    val invStatement1 =
      """CREATE VIE v AS SELECT * FROM t USING com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement1))

    val invStatement2 =
      """CREATE VIEW v AS SELEC * FROM t USING com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement2))

    val invStatement3 =
      """CREATE VIEW v AS SELECT * FROM t USIN com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement3))

    val invStatement5 =
      """CREATE VIEW v AS SELECT USING com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement5))
  }

  test("Handle correct DROP VIEW USING OPTIONS") {
    val statement = """DROP VIEW IF EXISTS v
                      |USING com.sap.spark.vora
                      |OPTIONS(discovery "1.1.1.1")""".stripMargin

    val parsed = ddlParser.parse(statement)
    assert(parsed.isInstanceOf[DropPersistentViewCommand])

    val actual = parsed.asInstanceOf[DropPersistentViewCommand]
    assertResult(true)(actual.allowNotExisting)
    assertResult(TableIdentifier("v"))(actual.identifier)
    assertResult("com.sap.spark.vora")(actual.provider)
    assertResult(Map[String, String]("discovery" -> "1.1.1.1"))(actual.options)
  }

  test("Handle incorrect DROP VIEW statements") {
    val invStatement1 =
      """DROP VIE v USING com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement1))

    val invStatement3 =
      """DROP VIEW v USIN com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement3))
  }

  test("Handle correct DROP VIEW statements") {
    val statement = ddlParser.parse("DROP VIEW v1")

    assertResult(
      UnresolvedDropCommand(
        View,
        allowNotExisting = false,
        TableIdentifier("v1"),
        cascade = false))(statement)
  }

  test("Parse correct SHOW TABLES USING statement") {
    val statement = """SHOW TABLES
                      |USING com.sap.spark.vora
                      |OPTIONS(discovery "1.1.1.1")""".stripMargin

    val parsed = ddlParser.parse(statement)
    assert(parsed.isInstanceOf[ShowTablesUsingCommand])

    val actual = parsed.asInstanceOf[ShowTablesUsingCommand]
    assertResult("com.sap.spark.vora")(actual.provider)
    assertResult(Map[String, String]("discovery" -> "1.1.1.1"))(actual.options)
  }

  test("Handle incorrect SHOW TABLES USING statement") {
    val invStatement1 =
      """SHOW TBLES USING com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement1))

    val invStatement2 =
      """SHOW TABLES USNG com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement2))
  }

  test("Parse correct DESCRIBE TABLE USING statement") {
    val statement = """DESCRIBE TABLE t1
                      |USING com.sap.spark.vora
                      |OPTIONS(discovery "1.1.1.1")""".stripMargin

    val parsed = ddlParser.parse(statement)
    assert(parsed.isInstanceOf[DescribeTableUsingCommand])

    val actual = parsed.asInstanceOf[DescribeTableUsingCommand]
    assertResult("com.sap.spark.vora")(actual.provider)
    assertResult(Map[String, String]("discovery" -> "1.1.1.1"))(actual.options)
  }

  test("Handle incorrect DESCRIBE TABLE USING statement") {
    val invStatement1 =
      """DESCRIBE TBLE t1 USING com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement1))

    val invStatement2 =
      """DESCRIBE TABLE t1 UZIN com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement2))
  }

  test("Parse correct CREATE DIMENSION VIEW USING OPTIONS") {
    val statement = """CREATE DIMENSION VIEW IF NOT EXISTS v
                      |AS SELECT * FROM t
                      |USING com.sap.spark.vora
                      |OPTIONS(discovery "1.1.1.1")""".stripMargin

    val parsed = ddlParser.parse(statement)
    assert(parsed.isInstanceOf[CreatePersistentViewCommand])

    val actual = parsed.asInstanceOf[CreatePersistentViewCommand]
    assert(actual.kind == Dimension)
    assertResult(Project(UnresolvedAlias(UnresolvedStar(None)) :: Nil,
      UnresolvedRelation(TableIdentifier("t"))))(actual.plan)
    assertResult(true)(actual.allowExisting)
    assertResult(TableIdentifier("v"))(actual.identifier)
    assertResult("com.sap.spark.vora")(actual.provider)
    assertResult(Map[String, String]("discovery" -> "1.1.1.1"))(actual.options)
  }

  test("Handle incorrect CREATE DIMENSION VIEW statements") {
    val invStatement1 =
      """CREATE DIMENSI VIEW v AS SELECT * FROM t USING com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement1))

    val invStatement2 =
      """CREATE DIMNESION v AS SELEC * FROM t USING com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement2))

    val invStatement3 =
      """CREATE DIMNESION VIEW v AS SELECT * FROM t USIN com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement3))

    val invStatement5 =
      """CREATE DIMNESION VIEW v AS SELECT USING com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement5))
  }

  test("Parse correct CREATE CUBE VIEW USING OPTIONS") {
    val statement = """CREATE CUBE VIEW IF NOT EXISTS v
                      |AS SELECT * FROM t
                      |USING com.sap.spark.vora
                      |OPTIONS(discovery "1.1.1.1")""".stripMargin

    val parsed = ddlParser.parse(statement)
    assert(parsed.isInstanceOf[CreatePersistentViewCommand])

    val actual = parsed.asInstanceOf[CreatePersistentViewCommand]
    assertResult(statement)(actual.viewSql)
    assert(actual.kind == CubeKind)
    assertResult(Project(UnresolvedAlias(UnresolvedStar(None)) :: Nil,
      UnresolvedRelation(TableIdentifier("t"))))(actual.plan)
    assertResult(true)(actual.allowExisting)
    assertResult(TableIdentifier("v"))(actual.identifier)
    assertResult("com.sap.spark.vora")(actual.provider)
    assertResult(Map[String, String]("discovery" -> "1.1.1.1"))(actual.options)
  }

  test("Handle incorrect CREATE CUBE VIEW statements") {
    val invStatement1 =
      """CREATE CUBEI VIEW v AS SELECT * FROM t USING com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement1))

    val invStatement2 =
      """CREATE CIUBE v AS SELEC * FROM t USING com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement2))

    val invStatement3 =
      """CREATE CBE VIEW v AS SELECT * FROM t USIN com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement3))

    val invStatement5 =
      """CREATE CUBEN VIEW v AS SELECT USING com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement5))
  }

  /* new engine DDL via raw DDL execution */
  test("test engine CREATE PARTITION FUNCTION") {
    val parsed = ddlParser
      .parse("CREATE PARTITION FUNCTION pf2(p1 INT) AS RANGE (p1) using com.sap.spark.engines")
    assert(parsed == RawDDLCommand(
      "CREATE PARTITION FUNCTION pf2(p1 INT) AS RANGE (p1)"))
  }

  test("test engine CREATE TABLE") {
    // beware of whitespace (please leave it)
    val parsed = ddlParser
      .parse(
        """CREATE TABLE tableName (col1 integer)
          |uSinG
          | com.sap.spark.engines""".stripMargin)
    assert(parsed == RawDDLCommand(
      "CREATE TABLE tableName (col1 integer)"))
  }
}
// scalastyle:on
