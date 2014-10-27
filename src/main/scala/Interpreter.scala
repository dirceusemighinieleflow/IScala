package org.refptr.iscala

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.repl.{SparkIMain, SparkILoop, SparkCommandLine}

import scala.collection.{immutable, mutable}

import scala.tools.nsc.interpreter.{NamedParam,  IR}
import scala.tools.nsc.util.Exceptional.unwrap

class Interpreter(classpath: String, args: Seq[String], embedded: Boolean=false, usejavacp: Boolean=true) extends InterpreterCompatibility {
  val commandLine = {
    val cl = new SparkCommandLine(args.toList, println(_))
    cl.settings.embeddedDefaults[this.type]
    cl.settings.usejavacp.value = usejavacp
    val totalClassPath = SparkILoop.getAddedJars.foldLeft(
      cl.settings.classpath.value)((l, r) => ClassPath.join(l, r))
    cl.settings.classpath.value =  ClassPath.join(totalClassPath, classpath)
    cl
  }



  if (embedded) {
        commandLine.settings.embeddedDefaults[this.type]
    }

    private val _classpath: String = {
        val cp = commandLine.settings.classpath
        cp.value = ClassPath.join(cp.value, classpath)
        logger.debug(s"classpath: ${cp.value}")
        cp.value
    }

    val output = new java.io.StringWriter
    val printer = new java.io.PrintWriter(output)

    //TODO: commit back to Spark
    val intp:SparkIMain = new SparkIMain(commandLine.settings, printer)

    val runner = new Runner(intp.classLoader)

    private var _session = new Session
    private var _n: Int = 0

    def session = _session
    def n = _n

    var sc: SparkContext = _


    val In = mutable.Map.empty[Int, String]
    val Out = mutable.Map.empty[Int, Any]

    this.initializeSpark()

  def initializeSpark() {
    sc = this.createSparkContext()
    val namedParam = NamedParam[SparkContext]("sc", sc)
    intp.beQuietDuring(bind(namedParam.name, namedParam.tpe, namedParam.value, immutable.List("@transient"))) match {
      case IR.Success => return
      case _ => throw new RuntimeException("Spark failed to initialize")
    }

    interpret("""
import org.apache.spark.SparkContext._
              """) match {
      case Results.Exception(_,_,_,ee) => throw new RuntimeException("SparkContext failed to be imported", ee)
      case Results.Value(value, tpe, repr) => return
      case _ => throw new RuntimeException("SparkContext failed to be imported")
    }
  }


  def reset() {
        finish()
        _session = new Session
        _n = 0
        In.clear()
        Out.clear()
        intp.reset()
    }

  def sparkCleanUp() {
    if (sc!=null) {
      sc.stop()
      sc = null
    }

    //    interpret("sc.stop()")
    //    match {
    //      case Results.Failure(exception) => throw exception
    //      case Results.Success(value) => return
    //      case _ => throw new RuntimeException("initialization failed to compile")
    //    }
  }
  def resetSpark() {
    synchronized {
      this.sparkCleanUp()

      this.initializeSpark()
    }
  }

  override def finalize() {
    try{
      synchronized {
        session.endSession(n)
      }
    }catch{
      case t: Throwable => throw t;
    }finally{
      super.finalize();
    }
  }

  def resetOutput() {
    output.getBuffer.setLength(0)
  }

  def finish() {
        _session.endSession(_n)
    }

    def isInitialized = intp.isInitializeComplete


    def nextInput(): Int = { _n += 1; _n }

    def storeInput(input: String) {
        In(n) = input
        session.addHistory(n, input)
    }

    def storeOutput(result: Results.Value, output: String) {
        Out(n) = result.value
        session.addOutputHistory(n, output)
        bind("_" + n, result.tpe, result.value)
    }

    def settings = commandLine.settings

    def classpath(cp: ClassPath) {
        settings.classpath.value = ClassPath.join(_classpath, cp.classpath)
    }

    def completion = new IScalaCompletion(intp)

    def withRunner(block: => Results.Result): Results.Result = {
        try {
            runner.execute { block } result()
        } finally {
            runner.clear()
        }
    }

    def withOutput[T](block: => T): (T, String) = {
        resetOutput()
        try {
            (block, output.toString)
        } finally {
            resetOutput()
        }
    }

    def withException[T](req: intp.Request)(block: => T): Either[T, Results.Result] = {
        try {
            Left(block)
        } catch {
            case original: Throwable =>
                val exception = unwrap(original)
                req.lineRep.bindError(original)

                val name = unmangle(exception.getClass.getName)
                val msg = Option(exception.getMessage).map(unmangle _) getOrElse ""
                val stacktrace = exception
                     .getStackTrace()
                     .takeWhile(_.getFileName != "<console>")
                     .map(stringify _)
                     .toList

                Right(Results.Exception(name, msg, stacktrace, exception))
        }
    }

    def runCode(moduleName: String, path:String, fieldName: String): Any = {
        import scala.reflect.runtime.{universe=>u}
        val mirror = u.runtimeMirror(intp.classLoader)
        val module = mirror.staticModule(moduleName)
        var instance = mirror.reflectModule(module).instance
        path.split("\\.").foreach{ x=>
          if(!x.isEmpty) {
            val im = mirror.reflect(instance)
            val fieldTerm = u.TermName(x)
            val field = im.symbol.typeSignature.member(fieldTerm).asTerm
            instance = im.reflectField(field).get
          }
        }
//        val im = mirror.reflect(instance)
//        val fieldTerm = u.TermName(fieldName)
//        val field = im.symbol.typeSignature.member(fieldTerm).asTerm
//        im.reflectField(field).get
      val im = mirror.reflect(instance)
      val fieldTerm = u.TermName(fieldName)
      val field = im.symbol.typeSignature.member(fieldTerm).asTerm
      im.reflectField(field).get

    }

    def display(req: intp.Request): Either[Data, Results.Result] = {
        import intp.memberHandlers.MemberHandler

        val displayName = "$display"
        val displayModule = req.lineRep.pathTo(displayName)
        val displayAccessPath =  req.accessPath
//        val displayPath = req.lineRep.pathTo(displayName) + req.accessPath
        val displayPath = req.lineRep.pathTo(displayName)

        object DisplayObjectSourceCode extends SparkIMain.CodeAssembler[MemberHandler] {
            import intp.global.NoSymbol

            val NS = "org.refptr.iscala"

            val displayResult = req.value match {
                case NoSymbol => s"$NS.Data()"
                case symbol   => s"$NS.display.Repr.stringify(${intp.originalPath(symbol.name)})"
            }

            val preamble =
                s"""
                |object $displayName {
                |  ${req.importsPreamble}
                |  val $displayName: $NS.Data = ${intp.executionWrapper} {
                |    $displayResult
                """.stripMargin

            val postamble =
                s"""
                |  }
                |  ${req.importsTrailer}
                |  val $displayName = this${req.accessPath}.$displayName
                |}
                """.stripMargin

            val generate = (handler: MemberHandler) => ""
        }

        val code = DisplayObjectSourceCode(req.handlers)

        if (!req.lineRep.compile(code)) Right(Results.Error)
        else withException(req) { runCode(displayModule, displayAccessPath, displayName) }.left.map {
            case Data(items @ _*) => Data(items map { case (mime, string) => (mime, unmangle(string)) }: _*)
        }
    }

    def loadAndRunReq(req: intp.Request): Results.Result = {
        import intp.memberHandlers.{MemberHandler,MemberDefHandler,ValHandler,DefHandler,AssignHandler}
        import intp.naming.sessionNames

        def definesValue(handler: MemberHandler): Boolean = {
            // MemberHandler.definesValue has slightly different meaning from what is
            // needed in loadAndRunReq. We don't want to eagerly evaluate lazy vals
            // or 0-arity defs, so we handle those cases here.
            if (!handler.definesValue) {
                false
            } else {
                handler match {
                    case handler: ValHandler if handler.mods.isLazy => false
                    case handler: DefHandler                        => false
                    case _ => true
                }
            }
        }

        def typeOf(handler: MemberHandler): String = {
            val symbolName = handler match {
                case handler: MemberDefHandler => handler.name
                case handler: AssignHandler    => handler.name
                case _                         => intp.global.nme.NO_NAME
            }

            req.lookupTypeOf(symbolName)
        }

        val handler = req.handlers.last
        val hasValue = definesValue(handler)

        val evalName = if (hasValue) sessionNames.result else sessionNames.print
        val evalResult = withException(req) { req.lineRep.call(evalName) }

        intp.recordRequest(req)

        evalResult match {
            case Left(value) =>
                lazy val valueType = typeOf(handler)

                if (hasValue && valueType != "Unit") {
                    display(req) match {
                        case Left(repr)    => Results.Value(value, valueType, repr)
                        case Right(result) => result
                    }
                } else
                    Results.NoValue
            case Right(result) => result
        }
    }

    def interpret(line: String): Results.Result = interpret(line, false)

    def interpret(line: String, synthetic: Boolean): Results.Result = {
        import intp.Request

        def requestFromLine(line: String, synthetic: Boolean): Either[IR.Result, Request] = {
            // XXX: Dirty hack to call a private method IMain.requestFromLine
            val method = classOf[SparkIMain].getDeclaredMethod("requestFromLine", classOf[String], classOf[Boolean])
            val args = Array(line, synthetic).map(_.asInstanceOf[AnyRef])
            method.setAccessible(true)
            method.invoke(intp, args: _*).asInstanceOf[Either[IR.Result, Request]]
        }

        requestFromLine(line, synthetic) match {
            case Left(IR.Incomplete) => Results.Incomplete
            case Left(_)             => Results.Error      // parse error
            case Right(req)          =>
                // null indicates a disallowed statement type; otherwise compile
                // and fail if false (implying e.g. a type error)
                if (req == null || !req.compile) Results.Error
                else withRunner { loadAndRunReq(req) }
        }
    }

    def bind(name: String, boundType: String, value: Any, modifiers: List[String] = Nil): IR.Result = {
        val imports = (intp.definedTypes ++ intp.definedTerms) match {
            case Nil   => "/* imports */"
            case names => names.map(intp.originalPath(_)).map("import " + _).mkString("\n  ")
        }

        val bindRep = new intp.ReadEvalPrint()
        val source = s"""
            |object ${bindRep.evalName} {
            |  $imports
            |  var value: ${boundType} = _
            |  def set(x: Any) = value = x.asInstanceOf[${boundType}]
            |}
            """.stripMargin

        bindRep.compile(source)
        bindRep.callEither("set", value) match {
            case Right(_) =>
                val line = "%sval %s = %s.value".format(modifiers map (_ + " ") mkString, name, bindRep.evalPath)
                intp.interpret(line)
            case Left(_) =>
                IR.Error
        }
    }

    def cancel() = runner.cancel()

    def stringify(obj: Any): String = unmangle(obj.toString)

    def unmangle(string: String): String = intp.naming.unmangle(string)

    def typeInfo(code: String, deconstruct: Boolean): Option[String] = {
        typeInfo(intp.symbolOfLine(code), deconstruct)
    }

    def typeInfo(symbol: intp.global.Symbol, deconstruct: Boolean): Option[String] = {
        if (symbol.exists) {
            Some(intp.global.exitingTyper {
                val info = symbol.info match {
                    case intp.global.NullaryMethodType(restpe) if symbol.isAccessor => restpe
                    case info                                                       => info
                }
                stringify(if (deconstruct) intp.deconstruct.show(info) else info)
            })
        } else None
    }

  lazy val appName: String = "ISpark"

  def createSparkContext(): SparkContext = {
    val execUri = System.getenv("SPARK_EXECUTOR_URI")
    val jars = SparkILoop.getAddedJars
    val conf = new SparkConf()
      .setMaster(getMaster())
      .setAppName(this.appName)
      .setJars(jars)
      .set("spark.repl.class.uri", intp.classServer.uri) //very important! spark treat REPL very differently
    if (execUri != null) {
      conf.set("spark.executor.uri", execUri)
    }
    if (System.getenv("SPARK_HOME") != null) {
      conf.setSparkHome(System.getenv("SPARK_HOME"))
    }
    new SparkContext(conf)
  }

  private def getMaster(): String = {
    val master = {
      val envMaster = sys.env.get("MASTER")
      val propMaster = sys.props.get("spark.master")
      propMaster.orElse(envMaster).getOrElse("local[*]")
    }
    master
  }
}
