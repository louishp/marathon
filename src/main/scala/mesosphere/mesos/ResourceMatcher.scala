package mesosphere.mesos

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.tasks.PortsMatcher
import mesosphere.mesos.protos.{ RangesResource, Resource }
import org.apache.mesos.Protos
import org.apache.mesos.Protos.Offer
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.collection.mutable

object ResourceMatcher {
  type Role = String
  val gpuPrefix = "gpu_core_" 

  case class ScalarMatch(requiredValue: Double, offeredValue: Double, role: String) {
    def matches: Boolean = requiredValue <= offeredValue
    def matchingRole: Option[Role] = if (matches) Some(role) else None

    override def toString: String = {
      if (matches) {
        s"SATISFIED ($requiredValue <= $offeredValue)"
      }
      else {
        s"NOT SATISFIED ($requiredValue > $offeredValue)"
      }
    }
  }

  private[this] val log = LoggerFactory.getLogger(getClass)

  case class ResourceMatch(cpuRole: Role, memRole: Role, diskRole: Role, gpusRole: Role, ports: Seq[RangesResource])

  //scalastyle:off method.length
  def matchResources(offer: Offer, app: AppDefinition, runningTasks: => Iterable[MarathonTask],
                     acceptedResourceRoles: Set[String] = Set("*")): Option[ResourceMatch] = {

    val groupedResources: Map[Role, mutable.Buffer[Protos.Resource]] = offer.getResourcesList.asScala.groupBy(_.getName)
    //log.info(s"Yubo -- groupedResources: [${groupedResources}]")

    def findScalarResource(name: String, requiredValue: Double): Option[ScalarMatch] =
      groupedResources.get(name).flatMap { resources =>
        val matchingScalarResources = resources.filter { resource =>
          acceptedResourceRoles(resource.getRole) && resource.hasScalar
        }

        val asMatches = matchingScalarResources.map { resource =>
          ScalarMatch(
            requiredValue = requiredValue,
            offeredValue = resource.getScalar.getValue,
            role = resource.getRole
          )
        }

        if (asMatches.isEmpty) {
          None
        }
        else {
          Some(asMatches.maxBy(_.offeredValue))
        }
      }

    def findGpuResource(prefix: String, requiredValue: Double): Option[ScalarMatch] =
      {
        val offerGpusResource = groupedResources.filterKeys(key => key.startsWith(prefix))

        var offerGpus = 0.0
        for ((key, resources) <- offerGpusResource) {
          val matchingScalarResources = resources.filter { resource =>
            acceptedResourceRoles(resource.getRole) && resource.hasScalar
          }
          //log.info(s"Yubo -- matchingScalarResources: ${matchingScalarResources}")
          val gpus = matchingScalarResources.map { resource =>
            resource.getScalar.getValue
          }.sum
          offerGpus += gpus
        }

        val asMatches =
          ScalarMatch(
            requiredValue = requiredValue,
            offeredValue = offerGpus,
            role = "*"
          )

        Some(asMatches)
      }

    val cpuMatchOpt: Option[ScalarMatch] = findScalarResource(Resource.CPUS, app.cpus)
    val memMatchOpt: Option[ScalarMatch] = findScalarResource(Resource.MEM, app.mem)
    val diskMatchOpt: Option[ScalarMatch] =
      if (app.disk == 0) {
        // Not used in builder since that checks for disk == 0 as well and ignores this role designation
        Some(ScalarMatch(requiredValue = 0.0, offeredValue = 0.0, role = ""))
      }
      else {
        findScalarResource(Resource.DISK, app.disk)
      }

    val gpusMatchOpt: Option[ScalarMatch] =
      if (app.gpus == 0) {
        // Not used in builder since that checks for disk == 0 as well and ignores this role designation
        Some(ScalarMatch(requiredValue = 0.0, offeredValue = 0.0, role = ""))
      }
      else {
        findGpuResource(gpuPrefix, app.gpus)
      }
    log.info(s"Yubo -- gpusMatchOpt: [${gpusMatchOpt}]")

    logUnsatisfiedResources(offer, acceptedResourceRoles, cpuMatchOpt, memMatchOpt, diskMatchOpti, gpusMatchOpt)

    def portsOpt: Option[Seq[RangesResource]] = new PortsMatcher(app, offer, acceptedResourceRoles).portRanges

    def meetsAllConstraints: Boolean = {
      lazy val tasks = runningTasks
      val badConstraints = app.constraints.filterNot { constraint =>
        Constraints.meetsConstraint(tasks, offer, constraint)
      }

      if (badConstraints.nonEmpty && log.isInfoEnabled) {
        log.info(
          s"Offer [${offer.getId.getValue}]. Constraints for app [${app.id}] not satisfied.\n" +
            s"The conflicting constraints are: [${badConstraints.mkString(", ")}]"
        )
      }

      badConstraints.isEmpty
    }

    for {
      cpuRole <- cpuMatchOpt.flatMap(_.matchingRole)
      memRole <- memMatchOpt.flatMap(_.matchingRole)
      diskRole <- diskMatchOpt.flatMap(_.matchingRole)
      gpusRole <- gpusMatchOpt.flatMap(_.matchingRole)
      portRanges <- portsOpt
      if meetsAllConstraints
    } yield ResourceMatch(cpuRole, memRole, diskRole, gpusRole, portRanges)
  }

  private[this] def logUnsatisfiedResources(offer: Offer,
                                            acceptedResourceRoles: Set[String],
                                            cpuMatchOpt: Option[ScalarMatch],
                                            memMatchOpt: Option[ScalarMatch],
                                            diskMatchOpt: Option[ScalarMatch],
                                            gpusMatchOpt: Option[ScalarMatch]): Unit = {
    if (log.isInfoEnabled) {
      val basicResourceMatches = Map(
        "cpu" -> cpuMatchOpt,
        "disk" -> diskMatchOpt,
        "mem" -> memMatchOpt,
        "gpus" -> gpusMatchOpt
      )

      if (!basicResourceMatches.values.forall(_.map(_.matches).getOrElse(false))) {
        val basicResourceString = basicResourceMatches.map {
          case (resource, Some(scalarMatch)) =>
            s"$resource $scalarMatch"
          case (resource, None) =>
            s"$resource not in offer"
        }.mkString(", ")
        log.info(
          s"Offer ID: [${offer.getId.getValue}]. Considered resources with roles: " +
            s"[${acceptedResourceRoles.mkString("")}]. " +
            s"Not all basic resources satisfied: $basicResourceString")
      }
    }
  }
}
