package spark.sql.operatorsJDBC

import algebra.target_api.TargetTreeNode
import spark.sql.SqlQuery.commonColumnsForJoinJDBC

case class LeftOuterJoinJDBC(lhs: TargetTreeNode, rhs: TargetTreeNode) extends JoinJDBC(lhs, rhs) {

  override def joinTypeSql: String = "LEFT OUTER JOIN"

  override def joinCondition: String = commonColumnsForJoinJDBC(lhsSchema, rhsSchema,lhsAlias, rhsAlias)
}

/*
How an left outer join should look like:

SELECT
*
FROM
  (
        SELECT
          p.id AS p0_0id,
          p.price AS p0_0price,
          p.name AS p0_0name,
          p.description AS p0_0description,
          p.idGoogle AS p0_0idGoogle,
          p.label AS p0_0label
        FROM
          ProductGoogle p
      )  YkCIJ
  LEFT OUTER JOIN (
        SELECT
          p.id AS p0_0id,
          p.price AS p0_0price,
          p.name AS p0_0name,
          p.description AS p0_0description,
          p.idGoogle AS p0_0idGoogle,
          p.label AS p0_0label,
          y.id AS y0_0id,
          y.price AS y0_0price,
          y.name AS y0_0name,
          y.description AS y0_0description,
          y.idGoogle AS y0_0idGoogle,
          y.label AS y0_0label,
          x.label AS x0_0label,
          x.id AS x0_0id,
          x.fromId AS x0_0fromId,
          x.toId AS x0_0toId
        FROM
          ProductGoogle p,
          producedbyGoogle x,
          ManufacturerGoogle y
        WHERE
          x.fromId = p.id
          AND x.toId = y.id
  ) zjUup ON (
    YkCIJ.p0_0id = zjUup.p0_0id
    AND YkCIJ.p0_0price = zjUup.p0_0price
    AND YkCIJ.p0_0name = zjUup.p0_0name
    AND YkCIJ.p0_0description = zjUup.p0_0description
    AND YkCIJ.p0_0idGoogle = zjUup.p0_0idGoogle
    AND YkCIJ.p0_0label = zjUup.p0_0label
    AND YkCIJ.p0_0table_label = zjUup.p0_0table_label
  )

 */

/*
SELECT * FROM (subquery) WHERE (True) --> remove this?
 */