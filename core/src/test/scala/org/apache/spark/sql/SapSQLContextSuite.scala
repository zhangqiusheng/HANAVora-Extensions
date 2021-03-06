package org.apache.spark.sql

import java.io.{ByteArrayOutputStream, ObjectOutputStream}

import org.mockito.Mockito
import org.scalatest.FunSuite

class SapSQLContextSuite extends FunSuite with GlobalSapSQLContext {

  test ("Check Spark Version"){
     val sap_sqlc = sqlContext.asInstanceOf[CommonSapSQLContext]
     // current spark runtime version shall be supported
     sap_sqlc.checkSparkVersion(List(org.apache.spark.SPARK_VERSION))

     // runtime exception for an unsupported version
     intercept[RuntimeException]{
      sap_sqlc.checkSparkVersion(List("some.unsupported.version"))
     }
  }

  test("Slightly different versions") {
    val sap_sqlc = sqlContext.asInstanceOf[CommonSapSQLContext]
    val spy_sap_sqlc = Mockito.spy(sap_sqlc)
    Mockito.when(spy_sap_sqlc.getCurrentSparkVersion())
      .thenReturn(org.apache.spark.SPARK_VERSION + "-CDH")

    // should not throw!
    spy_sap_sqlc.checkSparkVersion(spy_sap_sqlc.supportedVersions)

    Mockito.when(spy_sap_sqlc.getCurrentSparkVersion())
      .thenReturn("something- " + org.apache.spark.SPARK_VERSION)

    // should not throw!
    spy_sap_sqlc.checkSparkVersion(spy_sap_sqlc.supportedVersions)
  }

  test("Ignore USE keyword") {
    // Behaviour:
    // Every syntactically correct "USE [xyz...]" statement produces a UseStatementCommand.
    // If the spark "ignore_use_statement" property is missing or false
    // a SapParseError is thrown, else, the statement is ignored.

    val valid_use_statements = List(
      "USE DATABASE dude",
      "USE DATABASE a b c sdf sdfklasdfjklsd",
      "USE xyt dude",
      "USE      "
    )

    val invalid_use_statements = List(
      "asdsd USE    ",
      "CREATE TABLE USE tab001" // fails since USE is now a keyword
    )

    // should fail if "spark.vora.ignore_use_statements" prop is missing
    valid_use_statements.foreach { stmt =>
      intercept[SapParserException] {
        sqlContext.sql(stmt)
      }
    }
    invalid_use_statements.foreach { stmt =>
      intercept[SapParserException] {
        sqlContext.sql(stmt)
      }
    }

    // should fail if "spark.vora.ignore_use_statements" prop is "false"
    sqlContext.setConf(
      CommonSapSQLContext.PROPERTY_IGNORE_USE_STATEMENTS, "false")
    valid_use_statements.foreach { stmt =>
      intercept[SapParserException] {
        sqlContext.sql(stmt)
      }
    }
    invalid_use_statements.foreach { stmt =>
      intercept[SapParserException] {
        sqlContext.sql(stmt)
      }
    }

    // should fail if "spark.vora.ignore_use_statements" prop is "true"
    sqlContext.setConf(
      CommonSapSQLContext.PROPERTY_IGNORE_USE_STATEMENTS, "true")
    valid_use_statements.foreach { stmt =>
      sqlContext.sql(stmt)
    }
    // these should still fail even if "spark.vora.ignore_use_statements" prop is "true"
    invalid_use_statements.foreach { stmt =>
      intercept[SapParserException] {
        sqlContext.sql(stmt)
      }
    }
  }

  test("Ensure SapSQLContext stays serializable"){
    // relevant for Bug 92818
    // Remember that all class references in SapSQLContext must be serializable!
    val oos = new ObjectOutputStream(new ByteArrayOutputStream())
    oos.writeObject(sqlContext)
    oos.close()
  }

  test("Rand function") {
    sqlContext.sql(
      s"""
         |CREATE TABLE test (name varchar(20), age integer)
         |USING com.sap.spark.dstest
         |OPTIONS (
         |tableName "test"
         |)
       """.stripMargin)

    sqlContext.sql("SELECT * FROM test WHERE rand() < 0.1")
  }

  test("test version fields") {
    val sapSqlContext = sqlContext.asInstanceOf[CommonSapSQLContext]
    assert(sapSqlContext.EXTENSIONS_VERSION.isEmpty)
    assert(sapSqlContext.DATASOURCES_VERSION.isEmpty)
  }
}
