package org.apache.spark.sql.catalyst.plans.logical
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.types.StructField

/**
  * Class representing a direct sql call to a datasource implementing raw SQL interface
  * [[org.apache.spark.sql.sources.RawSqlSourceProvider]]
  */
case class SelectWith(
                       sqlCommand: String,
                       className: String,
                       output: Seq[Attribute])
  extends LeafNode {

  override lazy val resolved = true
}

/**
  * Unresolved representation, will be resolved by the Analyzer rule
  * [[org.apache.spark.sql.catalyst.analysis.ResolveSelectWith]]
  *
  * @param sqlCommand
  * @param className
  * @param fields if None, the schema gets inferred from the data source, if set, it is assumed to
  *               be the correct schema
  */
case class UnresolvedSelectWith(sqlCommand: String, className: String,
                                fields: Option[Seq[StructField]] = None) extends LeafNode {

  override def output: Seq[Attribute] = Nil

  override lazy val resolved = false
}
