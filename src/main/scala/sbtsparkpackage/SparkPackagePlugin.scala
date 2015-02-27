package sbtsparkpackage

import java.util.Locale

import sbt._
import Keys._
import Path.relativeTo
import sbtassembly.AssemblyPlugin
import sbtassembly.MappingSet
import sbtassembly.AssemblyKeys.{assembledMappings, assembly}
import scala.io.Source._
import scala.xml.{Elem, Node, NodeBuffer, NodeSeq, Null, Text, TopScope}
import java.io.{File => JavaFile}

object SparkPackagePlugin extends AutoPlugin {

  object autoImport {
    val sparkPackageName = settingKey[String]("The name of the Spark Package")
    val sparkComponents = settingKey[Seq[String]](
      "The components of Spark this package depends on. e.g. mllib, sql, graphx, streaming. Spark " +
        "Core is included by default if this key is not set.")
    val sparkVersion = settingKey[String]("The version of Spark to build against.")
    val sparkPackageDependencies = settingKey[Seq[String]]("The Spark Package dependencies")
    val sparkPackageNamespace = settingKey[String]("The namespace to use for shading while building " +
      "the assembly jar.")
    val spDist = taskKey[File]("Generate a zip archive for distribution on the Spark Packages website.")
    val spDistDirectory = settingKey[File]("Directory to output the zip archive.")
    val spPackage = taskKey[File]("Packs the Jar including Python files")
    val spMakePom = taskKey[File]("Generates the modified pom file")
    val spPublishLocal = taskKey[Unit]("Publish your package to local ivy repository")


    val defaultSPSettings = Seq(
      sparkPackageDependencies := Seq(),
      sparkComponents := Seq(),
      sparkVersion := "1.2.0",
      sparkPackageName := "zyxwvut/abcdefghi",
      spDistDirectory := baseDirectory.value
    )
  }

  import autoImport._

  override def requires = plugins.JvmPlugin && AssemblyPlugin
  override def trigger = allRequirements

  def listFilesRecursively(dir: File): Seq[File] = {
    val list = IO.listFiles(dir)
    list.filter(_.isFile) ++ list.filter(_.isDirectory).flatMap(listFilesRecursively)
  }

  override lazy val buildSettings: Seq[Setting[_]] = defaultSPSettings

  override lazy val projectSettings: Seq[Setting[_]] =
    Defaults.packageTaskSettings(spPackage, mappings in (Compile, spPackage)) ++
      baseSparkPackageSettings ++ spPublishingSettings

  val validatePackaging =
    Def.task {
      // Make sure Spark configuration is "provided"
      libraryDependencies.value.map { dep =>
        if (dep.organization == "org.apache.spark" && dep.configurations != Some("provided")) {
          sys.error("Please add any Spark dependencies by supplying the sparkVersion " +
            s"and sparkComponents. Please remove: $dep")
          false
        } else if (dep.organization == "org.apache.spark" && dep.revision != sparkVersion.value) {
          sys.error("Please add any Spark dependencies by supplying the sparkVersion " +
            s"and sparkComponents. Please remove: $dep")
          false
        } else {
          true
        }
      }.reduce(_ && _)
    }
  
  def normalizeName(s: String) = s.toLowerCase(Locale.ENGLISH).replaceAll("""\W+""", "-")

  def validateReturnSPDep(line: String): (String, String, String) = {
    val firstSplit = line.split("==")
    if (firstSplit.length != 2) {
      throw new IllegalArgumentException("Spark Package dependencies must be supplied as: " +
        s"`:package_name==:version` in spark-package-deps.txt. Found: $line")
    }
    val package_name = firstSplit(0)
    val version = firstSplit(1)
    val secondSplit = package_name.split("/")
    if (secondSplit.length != 2) {
      throw new IllegalArgumentException("Spark Package names must be supplied as: " +
        s"`:repo_owner_name/:repo_name` in spark-package-deps.txt. Found: $package_name")
    }
    (secondSplit(0), secondSplit(1), version)
  }

  def getPythonSparkPackageDeps(parent: Node, orgDeps: Option[Node]): NodeSeq = {
    val dependencies = orgDeps.orNull
    if (dependencies != null) {
      val pythonDeps = new File("python" + JavaFile.separator + "spark-package-deps.txt")
      if (pythonDeps.exists) {
        val buffer = new NodeBuffer
        for (line <- fromFile(pythonDeps).getLines) {
          val strippedLine = line.trim()
          if (strippedLine.length > 0 && !strippedLine.startsWith("#")) {
            val (groupId, artifactId, version) = validateReturnSPDep(strippedLine)
            def depExists: Boolean = {
              dependencies.child.foreach { dep =>
                if ((dep \ "groupId").text == groupId && (dep \ "artifactId").text == artifactId &&
                  (dep \ "version").text == version) {
                  return true
                }
              }
              false
            }
            if (!depExists) {
              buffer.append(new Elem(null, "dependency", Null, TopScope, false,
                new Elem(null, "groupId", Null, TopScope, false, new Text(groupId)),
                new Elem(null, "artifactId", Null, TopScope, false, new Text(artifactId)),
                new Elem(null, "version", Null, TopScope, false, new Text(version)))
              )
            }
          }
        }
        dependencies.child ++ buffer.result()
      } else {
        dependencies.child
      }
    } else {
      Seq[Node]()
    }
  }

  val listPythonBinaries: Def.Initialize[Task[Seq[(File, String)]]] = Def.taskDyn {
    if (validatePackaging.value) {
      Def.task {
        val pythonDirectory: Seq[File] = listFilesRecursively(baseDirectory.value / "python")
        val pythonBase = baseDirectory.value / "python"
        val pythonReqPath = baseDirectory.value / "python" / "requirements.txt"
        // Compile the python files
        if (pythonDirectory.length > 0) {
          s"python -m compileall ${(baseDirectory.value / "python")}" !
        }
        val pythonReq = if (pythonReqPath.exists()) Seq(pythonReqPath) else Seq()
        val pythonBinaries = pythonDirectory.filter { f =>
          f.getPath().indexOf("lib") == -1 && f.getPath().indexOf("bin") == -1 &&
            f.getPath().indexOf("doc") == -1
        }.filter(f => f.getPath().indexOf(".pyc") > -1)

        pythonBinaries ++ pythonReq pair relativeTo(pythonBase)
      }
    } else {
      Def.task { throw new IllegalArgumentException("Illegal dependencies.") }
    }
  }
  
  def spArtifactName(sp: String, version: String, ext: String=".jar"): String = {
    spBaseArtifactName(sp, version) + "." + ext
  }

  def spBaseArtifactName(sp: String, version: String): String = {
    val names = sp.split("/")
    require(names.length == 2,
      s"Please supply a valid sparkPackageName. sparkPackageName must be provided in " +
        s"the format: org_name/repo_name. Currently: $sp")
    require(names(0) != "abcdefghi" && names(1) != "zyxwvut",
      s"Please supply a sparkPackageName. sparkPackageName must be provided in " +
        s"the format: org_name/repo_name.")
    normalizeName(names(1)) + "-" + version
  }

  def spPackageKeys = Seq(spPackage)
  lazy val spPackages: Seq[TaskKey[File]] =
    for(task <- spPackageKeys; conf <- Seq(Compile, Test)) yield (task in conf)
  lazy val spArtifactTasks: Seq[TaskKey[File]] = spMakePom +: spPackages

  def spDeliverTask(config: TaskKey[DeliverConfiguration]) =
    (ivyModule in spDist, config, update, streams) map { (module, config, _, s) => IvyActions.deliver(module, config, s.log) }
  def spPublishTask(config: TaskKey[PublishConfiguration], deliverKey: TaskKey[_]) =
    (ivyModule in spDist, config, streams) map { (module, config, s) =>
      IvyActions.publish(module, config, s.log)
    } tag(Tags.Publish, Tags.Network)

  lazy val baseSparkPackageSettings: Seq[Setting[_]] = {
    Seq(
      resolvers += "Spark Packages Repo" at "https://dl.bintray.com/spark-packages/maven/",
      sparkPackageNamespace := sparkPackageName.value.replace("/", "_"),
      spDistDirectory := target.value,
      // add any Spark dependencies
      libraryDependencies ++= {
        val sparkComponentSet = sparkComponents.value.toSet
        if (sparkComponentSet.size == 0) {
          Seq("org.apache.spark" %% s"spark-core" % sparkVersion.value % "provided")
        } else {
          sparkComponentSet.map { component =>
            "org.apache.spark" %% s"spark-$component" % sparkVersion.value % "provided"
          }.toSeq
        }
      },
      // add any Spark Package dependencies
      libraryDependencies ++= sparkPackageDependencies.value.map { sparkPackage =>
        val splits = sparkPackage.split(":")
        require(splits.length == 2,
          "Spark Packages must be provided in the format: package_name:version.")
        val spVersion = splits(1)
        val names = splits(0).split("/")
        require(names.length == 2,
          "The package_name is provided in the format: org_name/repo_name.")
        names(0) % names(1) % spVersion
      },
      // add any Python binaries when making a distribution
      mappings in (Compile, spPackage) := (mappings in (Compile, packageBin)).value ++ listPythonBinaries.value,
      assembledMappings in assembly := (assembledMappings in assembly).value ++ 
        Seq(new MappingSet(None, listPythonBinaries.value.toVector)),
      spMakePom := {
        val config = makePomConfiguration.value
        IvyActions.makePom((ivyModule in spDist).value, config, streams.value.log)
        config.file
      },
      spDist := {
        val spArtifactName = spBaseArtifactName(sparkPackageName.value, version.value)
        val jar = spPackage.value
        val pom = spMakePom.value

        val zipFile = spDistDirectory.value / (spArtifactName + ".zip")

        IO.delete(zipFile)
        IO.zip(Seq(jar -> (spArtifactName + ".jar"), pom -> (spArtifactName + ".pom")), zipFile)
        
        println(s"\nZip File created at: $zipFile\n")

        zipFile
      },
      libraryDependencies in Test += "org.apache.spark" %% "spark-repl" % sparkVersion.value,
      console := {
        // Use test since Spark is a provided dependency.
        (runMain in Test).toTask(" org.apache.spark.repl.Main -usejavacp").value
      }
    )
  }
  
  def spProjectID = Def.setting {
    val names = sparkPackageName.value.split("/")
    require(names.length == 2,
      s"Please supply a valid sparkPackageName. sparkPackageName must be provided in " +
        s"the format: org_name/repo_name. Currently: ${sparkPackageName.value}")
    require(names(0) != "abcdefghi" && names(1) != "zyxwvut",
      s"Please supply a sparkPackageName. sparkPackageName must be provided in " +
        s"the format: org_name/repo_name.")

    val base = ModuleID(names(0), normalizeName(names(1)), version.value).artifacts(artifacts.value : _*)
    apiURL.value match {
      case Some(u) if autoAPIMappings.value => base.extra(CustomPomParser.ApiURLKey -> u.toExternalForm)
      case _ => base
    }
  }
  
  lazy val spPublishingSettings: Seq[Setting[_]] = Seq(
    publishLocalConfiguration in spPublishLocal := Classpaths.publishConfig(
      packagedArtifacts.in(spPublishLocal).value, Some(deliverLocal.value),
      checksums.in(publishLocal).value, logging = ivyLoggingLevel.value),
    packagedArtifacts in spPublishLocal <<= Classpaths.packaged(spArtifactTasks),
    packagedArtifact in spMakePom := (artifact in spMakePom value, spMakePom value),
    artifact in spMakePom := Artifact.pom(spBaseArtifactName(sparkPackageName.value, version.value)),
    artifacts <<= Classpaths.artifactDefs(spArtifactTasks),
    deliverLocal in spPublishLocal <<= spDeliverTask(deliverLocalConfiguration),
    spPublishLocal <<= spPublishTask(publishLocalConfiguration in spPublishLocal, deliverLocal in spPublishLocal),
    moduleSettings in spPublishLocal := new InlineConfiguration(spProjectID.value,
      projectInfo.value, allDependencies.value, dependencyOverrides.value, ivyXML.value,
      ivyConfigurations.value, defaultConfiguration.value, ivyScala.value, ivyValidate.value,
      conflictManager.value),
    ivyModule in spDist := { val is = ivySbt.value; new is.Module((moduleSettings in spPublishLocal).value) }
  )

}
