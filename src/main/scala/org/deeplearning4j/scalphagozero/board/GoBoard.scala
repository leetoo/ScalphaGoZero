package org.deeplearning4j.scalphagozero.board

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/**
  * Main Go board class, represents the board on which Go moves can be played.
  *
  * @author Max Pumperla
  */
class GoBoard(val row: Int, val col: Int) {

  import GoBoard._

  private var grid: mutable.Map[(Int, Int), GoString] = mutable.Map.empty
  private var hash: Long = 0L

  if (!neighborTables.keySet.contains((row, col)))
    initNeighborTable(row, col)
  if (!cornerTables.keySet.contains((row, col)))
    initCornerTable(row, col)

  private var neighborMap: mutable.Map[(Int, Int), List[Point]] =
    neighborTables.getOrElse((row, col), mutable.Map.empty)
  private var cornerMap: mutable.Map[(Int, Int), List[Point]] =
    cornerTables.getOrElse((row, col), mutable.Map.empty)

  def neighbors(point: Point): List[Point] = neighborMap.getOrElse((point.row, point.col), List.empty)

  def corners(point: Point): List[Point] = cornerMap.getOrElse((point.row, point.col), List.empty)

  def placeStone(player: Player, point: Point): Unit = {
    assert(isOnGrid(point))

    if (getGoString(point).isDefined) {
      println(" Illegal move attempted at: " + point.toCoords)
    } else {
      // 1. Examine adjacent points
      val adjacentSameColor = mutable.Set.empty[GoString]
      val adjacentOppositeColor = mutable.Set.empty[GoString]
      val liberties = mutable.Set.empty[(Int, Int)]

      for (neighbor: Point <- neighborMap((point.row, point.col))) {
        getGoString(neighbor) match {
          case None                                        => liberties += neighbor.toCoords
          case Some(goString) if goString.player == player => adjacentSameColor += goString
          case Some(goString)                              => adjacentOppositeColor += goString
        }
      }

      val newString =
        (adjacentSameColor += GoString(player, Set(point.toCoords), liberties.toSet)).reduce(_ mergedWith _)

      for (newStringPoint: (Int, Int) <- newString.stones)
        grid.put(newStringPoint, newString)

      hash ^= ZobristHashing.ZOBRIST((point.row, point.col, None)) // Remove empty-point hash code
      hash ^= ZobristHashing.ZOBRIST((point.row, point.col, Some(player))) // Add filled point hash code.

      // 3. Reduce liberties of any adjacent strings of the opposite color.
      // 4. If any opposite color strings now have zero liberties, remove them.
      for (otherColorString: GoString <- adjacentOppositeColor) {
        val replacement = otherColorString.withoutLiberty(point)
        if (replacement.numLiberties > 0) this.replaceString(replacement) else this.removeString(otherColorString)
      }
    }
  }

  private def removeString(goString: GoString): Unit =
    goString.stones.foreach { point =>
      neighborMap((point._1, point._2)).foreach { neighbor =>
        getGoString(neighbor) match {
          case Some(neighborString) if neighborString == goString =>
            this.replaceString(neighborString.withLiberty(Point(point._1, point._2)))
          case _ => ()
        }

        grid.remove(point)
      }

      hash ^= ZobristHashing.ZOBRIST((point._1, point._2, Some(goString.player))) //Remove filled point hash code.
      hash ^= ZobristHashing.ZOBRIST((point._1, point._2, None)) //Add empty point hash code.
    }

  private def replaceString(newString: GoString): Unit =
    for (point <- newString.stones)
      grid += (point -> newString)

  def isSelfCapture(player: Player, point: Point): Boolean = {
    val friendlyStrings: ListBuffer[GoString] = ListBuffer.empty[GoString]

    for (neighbor <- neighborMap((point.row, point.col))) {
      getGoString(neighbor) match {
        case None                                                     => return false
        case Some(neighborString) if neighborString.player == player  => friendlyStrings += neighborString
        case Some(neighborString) if neighborString.numLiberties == 1 => return false
        case _                                                        => ()
      }
    }

    friendlyStrings.forall(_.numLiberties == 1)
  }

  def willCapture(player: Player, point: Point): Boolean =
    neighborMap((point.row, point.col)).exists {
      getGoString(_) match {
        case Some(neighborString) if neighborString.player != player && neighborString.numLiberties == 1 => true
        case _                                                                                           => false
      }
    }

  def isOnGrid(point: Point): Boolean = 1 <= point.row && point.row <= row && 1 <= point.col && point.col <= col

  def getPlayer(point: Point): Option[Player] = grid.get(point.toCoords).map(_.player)

  def getGoString(point: Point): Option[GoString] = grid.get(point.toCoords)

  override def equals(obj: scala.Any): Boolean = {
    obj match {
      case other: GoBoard =>
        return this.row == other.row && this.col == other.col && this.grid.equals(other.grid)
      case _ =>
    }
    false
  }

  def zobristHash: Long = hash

  private def setBoardProperties(
      grid: mutable.Map[(Int, Int), GoString],
      hash: Long,
      neighborMap: mutable.Map[(Int, Int), List[Point]],
      cornerMap: mutable.Map[(Int, Int), List[Point]]
  ): Unit = {
    this.hash = hash
    this.grid = grid
    this.neighborMap = neighborMap
    this.cornerMap = cornerMap
  }

  override def clone(): GoBoard = {
    val newBoard = new GoBoard(this.row, this.col)
    newBoard.setBoardProperties(this.grid, this.hash, this.neighborMap, this.cornerMap)
    newBoard
  }

}

object GoBoard {

  def apply(boardHeight: Int, boardWidth: Int): GoBoard = new GoBoard(boardHeight, boardWidth)

  private val neighborTables: mutable.Map[(Int, Int), mutable.Map[(Int, Int), List[Point]]] = mutable.Map.empty
  private val cornerTables: mutable.Map[(Int, Int), mutable.Map[(Int, Int), List[Point]]] = mutable.Map.empty

  private def initNeighborTable(row: Int, col: Int): Unit = {
    val neighborMap: mutable.Map[(Int, Int), List[Point]] = mutable.Map.empty
    for (r <- 1 to row; c <- 1 to col) {
      val point = Point(r, c)
      val allNeighbors = point.neighbors
      val trueNeighbors =
        for (nb <- allNeighbors if 1 <= nb.row && nb.row <= row && 1 <= nb.col && nb.col <= col) yield nb
      neighborMap += ((r, c) -> trueNeighbors)
    }
    neighborTables += ((row, col) -> neighborMap)
  }

  private def initCornerTable(row: Int, col: Int): Unit = {
    val cornerMap: mutable.Map[(Int, Int), List[Point]] = mutable.Map.empty
    for (r <- 1 to row; c <- 1 to col) {
      val point = Point(r, c)
      val allCorners = List(
        Point(point.row - 1, point.col - 1),
        Point(point.row + 1, point.col + 1),
        Point(point.row - 1, point.col + 1),
        Point(point.row + 1, point.col - 1)
      )
      val trueCorners =
        for (nb <- allCorners if 1 <= nb.row && nb.row <= row && 1 <= nb.col && nb.col <= col) yield nb
      cornerMap += ((r, c) -> trueCorners)
    }
    cornerTables += ((row, col) -> cornerMap)
  }

}
