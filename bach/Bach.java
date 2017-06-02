/*
 * Java Shell Builder
 * Copyright (C) 2017 Christian Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// default package

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.function.*;
import java.util.logging.*;
import java.util.spi.*;
import java.util.stream.*;

/**
 * Java Shell Builder.
 *
 * @noinspection WeakerAccess, RedundantIfStatement, UnusedReturnValue, SameParameterValue,
 *     SimplifiableIfStatement, unused
 */
class Bach {

  Charset charset = StandardCharsets.UTF_8;
  final Map<String, ToolProvider> customTools = new TreeMap<>();
  Map<Folder, Path> folders = new EnumMap<>(Folder.class);
  Log log = new Log();
  PrintStream streamErr = System.err;
  PrintStream streamOut = System.out;
  final Tool tool = new Tool();

  /** Create and execute command. */
  void call(String executable, Object... arguments) {
    log.tag("call");
    command(executable, arguments).execute();
  }

  /** Create command instance from executable name and optional arguments. */
  Command command(String executable, Object... arguments) {
    return new Command(executable).addAll(arguments);
  }

  /** Compile all java modules (main and test). */
  void compile() {
    log.tag("compile");
    log.println(Level.CONFIG, "folder %s", folders.keySet());
    // TODO javac(Paths.get("src"), path(Folder.TARGET_COMPILE_MAIN),
    // tool.defaultJavacOptions.get());
  }

  /** Download the resource specified by its URI to the target directory. */
  Path download(URI uri, Path targetDirectory) {
    return download(uri, targetDirectory, Util.buildFileName(uri), targetPath -> true);
  }

  /** Download the resource from URI to the target directory using the provided file name. */
  Path download(URI uri, Path targetDirectory, String targetFileName, Predicate<Path> skip) {
    try {
      URL url = uri.toURL();
      Files.createDirectories(targetDirectory);
      Path targetPath = targetDirectory.resolve(targetFileName);
      URLConnection urlConnection = url.openConnection();
      FileTime urlLastModifiedTime = FileTime.fromMillis(urlConnection.getLastModified());
      if (Files.exists(targetPath)) {
        if (Files.getLastModifiedTime(targetPath).equals(urlLastModifiedTime)) {
          if (Files.size(targetPath) == urlConnection.getContentLengthLong()) {
            if (skip.test(targetPath)) {
              log.fine("download skipped - using `%s`", targetPath);
              return targetPath;
            }
          }
        }
        Files.delete(targetPath);
      }
      log.fine("download `%s` in progress...", uri);
      try (InputStream sourceStream = url.openStream();
          OutputStream targetStream = Files.newOutputStream(targetPath)) {
        sourceStream.transferTo(targetStream);
      }
      Files.setLastModifiedTime(targetPath, urlLastModifiedTime);
      log.fine("download `%s` completed", uri);
      log.info("stored `%s` [%s]", targetPath, urlLastModifiedTime.toString());
      return targetPath;
    } catch (Exception e) {
      throw new Error("should not happen", e);
    }
  }

  /** Format all source files. */
  void format(String... additionalArguments) {
    boolean replace = Boolean.getBoolean("bach.format.replace");
    format(replace, path(Folder.SOURCE), additionalArguments);
  }

  /** Format or validate source file formatting in specified paths. */
  void format(boolean replace, Path path, String... additionalArguments) {
    log.tag("format");
    ToolProvider format = tool.new GoogleJavaFormat(replace, path);
    format.run(streamOut, streamErr, additionalArguments);
  }

  /** Read Java class and interface definitions and compile them into bytecode and class files. */
  void javac(Path moduleSourcePath, Path destinationPath, Tool.JavacOptions options) {
    log.tag("javac");
    log.check(Files.exists(moduleSourcePath), "path `%s` does not exist", moduleSourcePath);
    Command command = command("javac");
    command.addOptions(options);
    // sets the destination directory for class files
    command.add("-d");
    command.add(destinationPath);
    // specify where to find input source files for multiple modules
    command.add("--module-source-path");
    command.add(moduleSourcePath);
    command.markDumpLimit(10);
    try {
      command.addAll(Files.walk(moduleSourcePath, 1).filter(Util::isJavaSourceFile));
    } catch (IOException e) {
      throw log.error(e, "gathering java source files in %s", moduleSourcePath);
    }
    command.execute();
  }

  /** Resolve path for given folder. */
  Path path(Folder folder) {
    Path path = folders.getOrDefault(folder, folder.path);
    return folder.parent == null ? path : path(folder.parent).resolve(path);
  }

  /** Override default folder path with a custom path. */
  void set(Folder folder, Path path) {
    folders.put(folder, path);
  }

  /** Set log level threshold. */
  void set(Level level) {
    log.level(level);
  }

  /** Register custom tool provider. */
  void set(ToolProvider toolProvider) {
    customTools.put(toolProvider.name(), toolProvider);
  }

  class Command {
    final List<String> arguments = new ArrayList<>();
    int dumpLimit = Integer.MAX_VALUE;
    int dumpOffset = Integer.MAX_VALUE;
    final String executable;

    Command(String executable) {
      this.executable = executable;
    }

    /** Conditionally add argument. */
    Command add(boolean condition, Object argument) {
      if (condition) {
        add(argument);
      }
      return this;
    }

    /** Add single argument with implicit null pointer check. */
    Command add(Object argument) {
      arguments.add(argument.toString());
      return this;
    }

    /** Add all arguments from the array. */
    Command addAll(Object... arguments) {
      for (Object argument : arguments) {
        add(argument);
      }
      return this;
    }

    /** Add all arguments from the stream. */
    Command addAll(Stream<?> stream) {
      // FIXME "try (stream)" is blocked by https://github.com/google/google-java-format/issues/155
      try {
        stream.forEach(this::add);
      } finally {
        stream.close();
      }
      return this;
    }

    /** Add all java source files. */
    Command addAllJavaFiles(Path path) {
      try {
        addAll(Files.walk(path).filter(Util::isJavaSourceFile));
      } catch (IOException e) {
        throw log.error(e, "gathering java source files in `%s` failed", path);
      }
      return this;
    }

    private void addOption(Object options, java.lang.reflect.Field field) throws Exception {
      // custom generator available?
      try {
        Object result = options.getClass().getDeclaredMethod(field.getName()).invoke(options);
        if (result instanceof List) {
          ((List<?>) result).forEach(this::add);
          return;
        }
      } catch (NoSuchMethodException e) {
        // fall-through
      }
      // additional arguments?
      String name = field.getName();
      Object value = field.get(options);
      if ("additionalArguments".equals(name) && value instanceof List) {
        ((List<?>) value).forEach(this::add);
        return;
      }
      // guess key and value
      String optionKey = "-" + name;
      // just a flag?
      if (field.getType() == boolean.class) {
        if (field.getBoolean(options)) {
          add(optionKey);
        }
        return;
      }
      // as-is
      add(optionKey);
      add(Objects.toString(value));
    }

    /** Reflect and add all options. */
    Command addOptions(Object options) {
      if (options == null) {
        return this;
      }
      try {
        for (java.lang.reflect.Field field : options.getClass().getDeclaredFields()) {
          // skip static and synthetic fields (like pointer to "this", "super", etc)
          if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
            continue;
          }
          addOption(options, field);
        }
      } catch (Exception e) {
        throw log.error(e, "reflecting options failed for %s", options);
      }
      return this;
    }

    /** Dump command properties to default logging output. */
    void dumpToLog(Level level) {
      if (log.isLevelSuppressed(level)) {
        return;
      }
      dumpToPrinter((format, args) -> log.println(level, format, args));
    }

    /** Dump command properties using the provided printer. */
    void dumpToPrinter(BiConsumer<String, Object[]> printer) {
      ListIterator<String> iterator = arguments.listIterator();
      printer.accept("%s", new Object[] {executable});
      while (iterator.hasNext()) {
        String argument = iterator.next();
        int nextIndex = iterator.nextIndex();
        String indent = nextIndex > dumpOffset || argument.startsWith("-") ? "" : "  ";
        printer.accept("%s%s", new Object[] {indent, argument});
        if (nextIndex >= dumpLimit) {
          int last = arguments.size() - 1;
          printer.accept("%s... [omitted %d arguments]", new Object[] {indent, last - nextIndex});
          printer.accept("%s%s", new Object[] {indent, arguments.get(last)});
          break;
        }
      }
    }

    /** Execute command throwing a runtime exception when the exit value is not zero. */
    int execute() {
      return execute(this::exitValueChecker);
    }

    /** Execute command with supplied exit value checker. */
    int execute(Consumer<Integer> exitValueChecker) {
      String tag = log.tag("execute");
      dumpToLog(Level.FINE);
      long start = System.currentTimeMillis();
      Integer exitValue = null;
      ToolProvider customTool = customTools.get(executable);
      if (customTool != null) {
        log.fine("executing custom `%s` tool in-process...", executable);
        exitValue = customTool.run(streamOut, streamErr, toArgumentsArray());
      }
      if (exitValue == null) {
        Optional<ToolProvider> tool = ToolProvider.findFirst(executable);
        if (tool.isPresent()) {
          log.fine("executing loaded `%s` tool in-process...", executable);
          exitValue = tool.get().run(streamOut, streamErr, toArgumentsArray());
        }
      }
      if (exitValue == null) {
        log.fine("executing external `%s` tool in new process...", executable);
        ProcessBuilder processBuilder = toProcessBuilder().redirectErrorStream(true);
        try {
          Process process = processBuilder.start();
          process.getInputStream().transferTo(streamOut);
          exitValue = process.waitFor();
        } catch (Exception e) {
          if (log.isLevelSuppressed(Level.FINE)) {
            dumpToLog(Level.SEVERE);
          }
          throw log.error(e, "execution of %s as process failed", executable);
        }
      }
      log.tag = tag;
      log.fine("%s finished after %d ms", executable, System.currentTimeMillis() - start);
      exitValueChecker.accept(exitValue);
      return exitValue;
    }

    /** Throw an {@link Error} when exit value is not zero. */
    void exitValueChecker(int value) {
      if (value == 0) {
        return;
      }
      throw new Error(String.format("exit value %d indicates an error", value));
    }

    /** Set dump offset and limit. */
    Command markDumpLimit(int limit) {
      this.dumpOffset = arguments.size();
      this.dumpLimit = arguments.size() + limit;
      return this;
    }

    /** Create new argument array based on this command's arguments. */
    String[] toArgumentsArray() {
      return arguments.toArray(new String[arguments.size()]);
    }

    /** Create new {@link ProcessBuilder} instance based on this command setup. */
    ProcessBuilder toProcessBuilder() {
      ArrayList<String> command = new ArrayList<>(1 + arguments.size());
      command.add(executable);
      command.addAll(arguments);
      return new ProcessBuilder(command);
    }
  }

  enum Folder {
    JDK_HOME(Util.buildJdkHome()),
    JDK_HOME_BIN(JDK_HOME, Paths.get("bin")),
    JDK_HOME_MODS(JDK_HOME, Paths.get("jmods")),
    //
    AUXILIARY(Paths.get(".bach")),
    DEPENDENCIES(AUXILIARY, Paths.get("dependencies")),
    TOOLS(AUXILIARY, Paths.get("tools")),
    //
    SOURCE(Paths.get("src")),
    //
    TARGET(Paths.get("target", "bach")),
    TARGET_COMPILE_MAIN(TARGET, Paths.get("main", "java"));

    final Folder parent;
    final Path path;

    Folder(Folder parent, Path path) {
      this.parent = parent;
      this.path = path;
    }

    Folder(Path path) {
      this(null, path);
    }
  }

  class Log {

    String tag = "init";
    int threshold = Level.FINE.intValue();

    /** Check condition and on false throw an {@link AssertionError}. */
    void check(boolean condition, String format, Object... args) {
      if (condition) {
        return;
      }
      throw new AssertionError(String.format(format, args));
    }

    Error error(Throwable cause, String format, Object... args) {
      String message = String.format(format, args);
      printTagAndMessage(Level.SEVERE, message);
      throw new Error(message, cause);
    }

    /** Print message at level: {@link Level#FINE} */
    void fine(String format, Object... args) {
      println(Level.FINE, format, args);
    }

    /** Print message at level: {@link Level#INFO} */
    void info(String format, Object... args) {
      println(Level.INFO, format, args);
    }

    /** Return {@code true} if the level is not suppressed. */
    boolean isLevelActive(Level level) {
      return !isLevelSuppressed(level);
    }

    /** Return {@code true} if the level value is below the current threshold. */
    boolean isLevelSuppressed(Level level) {
      return level.intValue() < threshold;
    }

    /** Set current threshold to the value reported by the passed level instance. */
    void level(Level level) {
      this.threshold = level.intValue();
    }

    /** Print formatted message line if the level is active. */
    void println(Level level, String format, Object... args) {
      if (isLevelSuppressed(level)) {
        return;
      }
      if (args.length == 1 && args[0] instanceof Collection) {
        for (Object arg : (Collection<?>) args[0]) {
          if (arg instanceof Folder) {
            arg = arg + " -> " + Bach.this.path((Folder) arg);
          }
          printTagAndMessage(level, format, arg);
        }
        return;
      }
      printTagAndMessage(level, format, args);
    }

    /** Print tag and formatted message line. */
    private void printTagAndMessage(Level level, String format, Object... args) {
      PrintStream out = Bach.this.streamOut;
      if (threshold < Level.INFO.intValue()) {
        out.printf("%6s|", level.getName().toLowerCase());
      }
      out.printf("%7s| ", tag);
      out.println(String.format(format, args));
    }

    /** Set current log tag if it differs from the old one. */
    String tag(String tag) {
      String old = this.tag;
      if (!Objects.equals(old, tag)) {
        this.tag = tag;
        println(Level.CONFIG, "");
      }
      return old;
    }
  }

  class Tool {

    class GoogleJavaFormat implements ToolProvider {

      final String SRV = "https://jitpack.io";
      final String PTH = "com/github/sormuras";
      final String GJF = "google-java-format";
      final String VER = "validate-SNAPSHOT";
      final String VAR = "-all-deps";
      final String FORMAT = "%1$s/%2$s/%3$s/%3$s/%4$s/%3$s-%4$s%5$s.jar";
      final URI uri = URI.create(String.format(FORMAT, SRV, PTH, GJF, VER, VAR));
      final List<Path> paths;
      final boolean replace;

      GoogleJavaFormat(boolean replace, Path... paths) {
        this.replace = replace;
        this.paths = List.of(paths);
      }

      private boolean isConsumableByFormat(Path path) {
        String name = path.getFileName().toString();
        if (name.equals("module-info.java")) {
          return false; // see https://github.com/google/google-java-format/issues/75
        }
        return Util.isJavaSourceFile(path);
      }

      @Override
      public String name() {
        return "format";
      }

      @Override
      public int run(PrintWriter out, PrintWriter err, String... additionalArguments) {
        String mode = replace ? "replace" : "validate";
        log.tag("format");
        log.fine("mode=%s", mode);
        Path jar = download(uri, path(Folder.TOOLS).resolve("google-java-format"));
        Command command =
            new Command(path(Folder.JDK_HOME_BIN).resolve("java").toString())
                .add("-jar")
                .add(jar)
                .add("--" + mode)
                .addAll((Object[]) additionalArguments)
                .markDumpLimit(10);
        // collect consumable .java source files
        int exitValue = 0;
        int[] count = {0};
        for (Path path : paths) {
          log.fine("%s `%s`...", replace ? "formatting" : "validating", path);
          try {
            Files.walk(path)
                .filter(this::isConsumableByFormat)
                .map(Path::toString)
                .peek(name -> count[0]++)
                .forEach(command::add);
          } catch (IOException e) {
            throw log.error(e, "walking path `%s` failed", path);
          }
          exitValue += command.execute();
        }
        log.info("%d files %s", count[0], replace ? "formatted" : "validated");
        return exitValue;
      }
    }

    class JavacOptions {
      /** User-defined arguments. */
      List<String> additionalArguments = Collections.emptyList();

      /** Output source locations where deprecated APIs are used. */
      boolean deprecation = true;

      /** Specify character encoding used by source files. */
      Charset encoding = Bach.this.charset;

      /** Terminate compilation if warnings occur. */
      boolean failOnWarnings = true;

      /** Specify where to find application modules. */
      List<Path> modulePaths = List.of(Bach.this.path(Folder.DEPENDENCIES));

      /** Generate metadata for reflection on method parameters. */
      boolean parameters = true;

      /** Output messages about what the compiler is doing. */
      boolean verbose = log.isLevelActive(Level.FINEST);

      List<String> encoding() {
        if (Charset.defaultCharset().equals(encoding)) {
          return Collections.emptyList();
        }
        return List.of("-encoding", encoding.name());
      }

      List<String> failOnWarnings() {
        return failOnWarnings ? List.of("-Werror") : Collections.emptyList();
      }

      List<String> modulePaths() {
        return List.of("--module-path", Util.join(modulePaths));
      }
    }

    Supplier<Tool.JavacOptions> defaultJavacOptions = JavacOptions::new;
  }

  interface Util {

    /** Extract the file name from the uri. */
    static String buildFileName(URI uri) {
      String urlString = uri.getPath();
      int begin = urlString.lastIndexOf('/') + 1;
      return urlString.substring(begin).split("\\?")[0].split("#")[0];
    }

    /** Return path to JDK installation directory. */
    static Path buildJdkHome() {
      // try current process information: <JDK_HOME>/bin/java[.exe]
      Path executable = ProcessHandle.current().info().command().map(Paths::get).orElse(null);
      if (executable != null) {
        Path path = executable.getParent(); // <JDK_HOME>/bin
        if (path != null) {
          return path.getParent(); // <JDK_HOME>
        }
      }
      // next, examine system environment...
      String jdkHome = System.getenv("JDK_HOME");
      if (jdkHome != null) {
        return Paths.get(jdkHome);
      }
      String javaHome = System.getenv("JAVA_HOME");
      if (javaHome != null) {
        return Paths.get(javaHome);
      }
      // still here? not so good... try with default (not-existent) path
      return Paths.get("jdk-" + Runtime.version().major());
    }

    static Path cleanTree(Path root, boolean keepRoot) {
      return cleanTree(root, keepRoot, __ -> true);
    }

    static Path cleanTree(Path root, boolean keepRoot, Predicate<Path> filter) {
      try {
        if (Files.notExists(root)) {
          if (keepRoot) {
            Files.createDirectories(root);
          }
          return root;
        }
        Files.walk(root)
            .filter(p -> !(keepRoot && root.equals(p)))
            .filter(filter)
            .sorted((p, q) -> -p.compareTo(q))
            .forEach(Util::delete);
        // log.fine("deleted tree `%s`%n", root);
        return root;
      } catch (Exception e) {
        throw new Error("should not happen", e);
      }
    }

    static void copyTree(Path source, Path target) {
      if (!Files.exists(source)) {
        return;
      }
      // log.fine("copy `%s` to `%s`%n", source, target);
      try {
        Files.createDirectories(target);
        Files.walkFileTree(
            source,
            EnumSet.of(FileVisitOption.FOLLOW_LINKS),
            Integer.MAX_VALUE,
            new SimpleFileVisitor<>() {
              @Override
              public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes bfa)
                  throws IOException {
                Path targetdir = target.resolve(source.relativize(dir));
                try {
                  Files.copy(dir, targetdir);
                } catch (FileAlreadyExistsException e) {
                  if (!Files.isDirectory(targetdir)) {
                    throw e;
                  }
                }
                return FileVisitResult.CONTINUE;
              }

              @Override
              public FileVisitResult visitFile(Path file, BasicFileAttributes bfa)
                  throws IOException {
                Files.copy(
                    file,
                    target.resolve(source.relativize(file)),
                    StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
              }
            });
      } catch (Exception e) {
        throw new Error("should not happen", e);
      }
    }

    static void delete(Path path) {
      try {
        Files.deleteIfExists(path);
      } catch (Exception e) {
        throw new Error("should not happen", e);
      }
    }

    static Stream<Path> findDirectories(Path root) {
      try {
        return Files.find(root, 1, (path, attr) -> Files.isDirectory(path))
            .filter(path -> !root.equals(path));
      } catch (Exception e) {
        throw new Error("should not happen", e);
      }
    }

    static Stream<String> findDirectoryNames(Path root) {
      return findDirectories(root).map(root::relativize).map(Path::toString);
    }

    /** Return {@code true} if the path points to a canonical Java compilation unit. */
    static boolean isJavaSourceFile(Path path) {
      if (!Files.isRegularFile(path)) {
        return false;
      }
      String name = path.getFileName().toString();
      if (name.chars().filter(c -> c == '.').count() != 1) {
        return false;
      }
      return name.endsWith(".java");
    }

    /** Join paths to a single representation using system-dependent path-separator character. */
    static String join(List<Path> paths) {
      List<String> locations = paths.stream().map(Object::toString).collect(Collectors.toList());
      return String.join(File.pathSeparator, locations);
    }

    static void moveModuleInfo(Path path) {
      Path source = path.resolve("module-info.test");
      if (!Files.exists(source)) {
        return;
      }
      Path target = path.resolve("module-info.java");
      try {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
      } catch (Exception e) {
        throw new Error("should not happen", e);
      }
    }
  }
}