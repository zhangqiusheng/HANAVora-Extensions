package org.apache.spark.sql.sources

import org.apache.spark.sql.catalyst.plans.logical._

sealed trait MultiAbstractViewProvider

/**
  * An interface that marks a data source as a cube view provider.
  * It allows the user to push down views to the target data source where the cube
  * view's logical plan is serialized in the data source catalog.
  *
  * TODO (YH) we should discuss whether we want to support updating the view.
  */
trait CubeViewProvider extends MultiAbstractViewProvider {

  /**
    * Save the view in the catalog of the data source.
    *
    * @param createViewInput The parameters for the view creation.
    */
  def createCubeView(createViewInput: CreateViewInput): ViewHandle


  /**
    * Drops the cube view from the catalog of the data source.
    *
    * @param dropViewInput The parameters to drop a view.
    */
  def dropCubeView(dropViewInput: DropViewInput): Unit

  private[sql] def toSingleCubeViewProvider: AbstractViewProvider[PersistedCubeView] = {
    new BaseAbstractViewProvider[PersistedCubeView] {
      override def drop(dropViewInput: DropViewInput): Unit = dropCubeView(dropViewInput)

      override def create(createViewInput: CreateViewInput): ViewHandle =
        createCubeView(createViewInput)
    }
  }
}

/**
  * An interface that marks a data source as a dimension view provider.
  * It allows the user to push down views to the target data source where the dimension
  * view's logical plan is serialized in the data source catalog.
  *
  * TODO (YH) we should discuss whether we want to support updating the view.
  */
trait DimensionViewProvider extends MultiAbstractViewProvider {

  /**
    * Save the view in the catalog of the data source.
    *
    * @param createViewInput The parameters for the view creation.
    */
  def createDimensionView(createViewInput: CreateViewInput): ViewHandle


  /**
    * Drops the dimension view from the catalog of the data source.
    *
    * @param dropViewInput The parameters to drop the view.
    */
  def dropDimensionView(dropViewInput: DropViewInput): Unit

  private[sql] def toSingleDimensionViewProvider: AbstractViewProvider[PersistedDimensionView] = {
    new BaseAbstractViewProvider[PersistedDimensionView] {
      override def drop(dropViewInput: DropViewInput): Unit = {
        dropDimensionView(dropViewInput)
      }

      override def create(createViewInput: CreateViewInput): ViewHandle = {
        createDimensionView(createViewInput)
      }
    }
  }
}

/**
  * An interface that marks a data source as a view provider.
  * It allows the user to push down views to the target data source where the view's logical plan is
  * serialized in the data source catalog.
  *
  * TODO (YH) we should discuss whether we want to support updating the view.
  */
trait ViewProvider extends MultiAbstractViewProvider {

  /**
    * Save the view in the catalog of the data source.
    *
    * @param createViewInput The parameters to create the view.
    */
  def createView(createViewInput: CreateViewInput): ViewHandle


  /**
    * Drops the view from the catalog of the data source.
    *
    * @param dropViewInput The parameters to drop the view.
    */
  def dropView(dropViewInput: DropViewInput): Unit


  private[sql] def toSingleViewProvider: AbstractViewProvider[PersistedView] = {
    new BaseAbstractViewProvider[PersistedView] {
      override def drop(dropViewInput: DropViewInput): Unit = dropView(dropViewInput)

      override def create(createViewInput: CreateViewInput): ViewHandle = {
        createView(createViewInput)
      }
    }
  }
}

object MultiAbstractViewProvider {
  case class TagMatcher(viewKind: sql.ViewKind) {
    def unapply(arg: MultiAbstractViewProvider): Option[AbstractViewProvider[_]] =
      (arg, viewKind) match {
        case (v: ViewProvider, sql.Plain) =>
          Some(v.toSingleViewProvider)
        case (c: CubeViewProvider, sql.Cube) =>
          Some(c.toSingleCubeViewProvider)
        case (d: DimensionViewProvider, sql.Dimension) =>
          Some(d.toSingleDimensionViewProvider)
        case _ =>
          None
      }
  }

  def matcherFor(viewKind: sql.ViewKind): TagMatcher =
    TagMatcher(viewKind)
}
