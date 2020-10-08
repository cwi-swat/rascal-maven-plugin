/*******************************************************************************
 * Copyright (c) 2019 CWI
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   * Jurgen J. Vinju - Jurgen.Vinju@cwi.nl - CWI
 */
package org.rascalmpl.maven;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Array;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import java.util.stream.IntStream;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.checkerframework.checker.units.qual.A;
import org.codehaus.plexus.compiler.util.scan.InclusionScanException;
import org.codehaus.plexus.compiler.util.scan.StaleSourceScanner;
import org.codehaus.plexus.compiler.util.scan.mapping.SuffixMapping;
import org.rascalmpl.debug.IRascalMonitor;
import org.rascalmpl.exceptions.Throw;
import org.rascalmpl.interpreter.Evaluator;
import org.rascalmpl.interpreter.IEvaluator;
import org.rascalmpl.interpreter.env.GlobalEnvironment;
import org.rascalmpl.interpreter.env.ModuleEnvironment;
import org.rascalmpl.interpreter.result.Result;
import org.rascalmpl.interpreter.utils.RascalManifest;
import org.rascalmpl.library.util.PathConfig;
import org.rascalmpl.uri.URIResolverRegistry;
import org.rascalmpl.uri.URIUtil;
import org.rascalmpl.util.ConcurrentSoftReferenceObjectPool;
import org.rascalmpl.values.ValueFactoryFactory;

import io.usethesource.vallang.IConstructor;
import io.usethesource.vallang.IList;
import io.usethesource.vallang.IListWriter;
import io.usethesource.vallang.ISet;
import io.usethesource.vallang.ISourceLocation;
import io.usethesource.vallang.IString;
import io.usethesource.vallang.IValue;
import io.usethesource.vallang.IValueFactory;
import io.usethesource.vallang.exceptions.FactTypeUseException;
import io.usethesource.vallang.io.StandardTextReader;

/**
 * Maven Goal for Rascal compilation. The input is a list of
 * Rascal source folders, the output is for each module in the source tree:
 * a .tpl file and possibly/optionally a .class file. Also a list of errors
 * and warnings is printed on stderr. 
 * 
 * TODO The Mojo currently uses the Rascal interpreter to run 
 * what is currently finished of the compiler and type-checker. Since the compiler 
 * is not finished yet, the behavior of this mojo is highly experimental and fluid. 
 * 
 * TODO When the compiler will be bootstrapped, this mojo will run a generated
 * compiler instead of the source code of the compiler inside the Rascal interpreter.
 * 
 * TODO This Mojo always requires a 'boot' parameter which points to the file location of the
 * source of the compiler. After the bootstrap, this parameter will become optional.
 * 
 */
@Mojo(name="compile", defaultPhase = LifecyclePhase.COMPILE, requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class CompileRascalMojo extends AbstractMojo
{
	private static final String UNEXPECTED_ERROR = "unexpected error during Rascal compiler run";
	private static final String MAIN_COMPILER_MODULE = "lang::rascalcore::check::Checker";
	private static final String INFO_PREFIX_MODULE_PATH = "\trascal module path addition: ";
	private static final URIResolverRegistry reg = URIResolverRegistry.getInstance();
	private static final IValueFactory VF = ValueFactoryFactory.getValueFactory();
	
	@Parameter(defaultValue="${project}", readonly=true, required=true)
	private MavenProject project;

	@Parameter(defaultValue = "${project.build.outputDirectory}", property = "bin", required = true )
	private String bin;

	@Parameter(property = "srcs", required = true )
	private List<String> srcs;
	
	@Parameter(property = "srcIgnores", required = false )
	private List<String> srcIgnores;    

	@Parameter(property = "libs", required = false )
	private List<String> libs;

	@Parameter(property = "errorsAsWarnings", required = false, defaultValue = "false" )
	private boolean errorsAsWarnings;

	@Parameter(property = "warningsAsErrors", required = false, defaultValue = "false" )
	private boolean warningsAsErrors;

	@Parameter(property="enableStandardLibrary", required = false, defaultValue="true")
	private boolean enableStandardLibrary;

	@Parameter(property="parallel", required = false, defaultValue="false")
	private boolean parallel;

	@Parameter(property = "parallelPreChecks", required = false )
	private List<String> parallelPreChecks;

	private MojoRascalMonitor monitor;

	private static String toClassPath(URL... urls) {
	    return Arrays.stream(urls)
				.map(u -> {
					try {
						return u.toURI();
					} catch (URISyntaxException e) {
					    throw new RuntimeException(e);
					}
				})
				.map(File::new)
				.map(File::toString)
				.collect(Collectors.joining(System.getProperty("path.separator")));
	}

	private Evaluator makeEvaluator(OutputStream err, OutputStream out) throws URISyntaxException, FactTypeUseException, IOException {
		safeLog(l -> l.info("start loading the compiler"));
		GlobalEnvironment heap = new GlobalEnvironment();
		Evaluator eval = new Evaluator(ValueFactoryFactory.getValueFactory(), System.in, err, out, new ModuleEnvironment("***MVN Rascal Compiler***", heap), heap);
		URL rascalJarFile = ValueFactoryFactory.class.getProtectionDomain().getCodeSource().getLocation();
		URL vallangJarFile = IValueFactory.class.getProtectionDomain().getCodeSource().getLocation();
		eval.getConfiguration().setRascalJavaClassPathProperty(toClassPath(rascalJarFile, vallangJarFile));

		monitor = new MojoRascalMonitor(getLog(), false);
		eval.setMonitor(monitor);

		safeLog(l -> l.info(INFO_PREFIX_MODULE_PATH + "|lib://typepal/|"));
        eval.addRascalSearchPath(URIUtil.correctLocation("lib", "typepal", ""));

		safeLog(l -> l.info(INFO_PREFIX_MODULE_PATH + "|lib://rascal-core/|"));
		eval.addRascalSearchPath(URIUtil.correctLocation("lib", "rascal-core", ""));
		
		safeLog(l -> l.info(INFO_PREFIX_MODULE_PATH + "|std:///|"));
		eval.addRascalSearchPath(URIUtil.rootLocation("std"));

		safeLog(l -> l.info("\timporting " + MAIN_COMPILER_MODULE));
		eval.doImport(monitor, MAIN_COMPILER_MODULE);

		safeLog(l -> l.info("done loading the compiler"));

		return eval;
	}

	public void execute() throws MojoExecutionException {
		try {
			ISourceLocation binLoc = location(bin);
			List<ISourceLocation> srcLocs = locations(srcs);
			List<ISourceLocation> ignoredLocs = locations(srcIgnores);
			List<ISourceLocation> libLocs = locations(libs);
			
			getLog().info("configuring paths");
			for (ISourceLocation src : srcLocs) {
				getLog().info("\tregistered source location: " + src);
			}
			
			for (ISourceLocation ignore : ignoredLocs) {
				getLog().warn("\tignoring sources in: " + ignore);
			}
			
			getLog().info("checking if any files need compilation");

			IList todoList = getTodoList(binLoc, srcLocs, ignoredLocs);

			if (!todoList.isEmpty()) {
				getLog().info("stale source files have been found:");
				for (IValue todo : todoList) {
					getLog().info("\t" + todo);
				}
			}
			else {
				getLog().info("no stale source files have been found, skipping compilation.");
				return;
			}
			
			if (enableStandardLibrary) {
				libLocs.add(URIUtil.correctLocation("lib", "rascal", ""));
			}
			
			// complete libraries with maven artifacts which include a META-INF/RASCAL.MF file
			collectDependentArtifactLibraries(libLocs);
			
			for (ISourceLocation lib : libLocs) {
				getLog().info("\tregistered library location: " + lib);
			}
			
			getLog().info("paths have been configured");
			

			PathConfig pcfg = new PathConfig(srcLocs, libLocs, binLoc);

			IList messages = runChecker(monitor, todoList, pcfg);


			getLog().info("checker is done, reporting errors now.");
			handleMessages(pcfg, messages);
			getLog().info("error reporting done");
			
			return;
		} catch (URISyntaxException e) {
			throw new MojoExecutionException(UNEXPECTED_ERROR, e);
		} catch (IOException e) {
			throw new MojoExecutionException(UNEXPECTED_ERROR, e);
		} catch (InclusionScanException e) {
			throw new MojoExecutionException(UNEXPECTED_ERROR, e);
		} catch (Throw e) {
		    getLog().error(e.getLocation() + ": " + e.getMessage());
		    getLog().error(e.getTrace().toString());
		    throw new MojoExecutionException(UNEXPECTED_ERROR, e); 
		}
	}

	private static int parallelAmount() {
	    // check available CPUs
		long result = Runtime.getRuntime().availableProcessors() / 2;
		if (result < 2) {
			return 1;
		}
		// check available memory
		result = Math.min(result, Runtime.getRuntime().maxMemory() / (1024 * 1024));
		if (result < 2) {
			return 1;
		}
		return (int) Math.max(4, result);
	}


	private void safeLog(Consumer<Log> action) {
		Log log = getLog();
		synchronized (log) {
			action.accept(log);
		}
	}


	private final static class SynchronizedOutputStream extends OutputStream {
		private final OutputStream target;

		private SynchronizedOutputStream(OutputStream target) {
		    this.target = target;

		}

		@Override
		public void close() throws IOException {
			target.close();
		}

		@Override
		public void flush() throws IOException {
		    synchronized (target) {
		    	target.flush();
			}
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			synchronized (target) {
				target.write(b, off, len);
			}
		}

		@Override
		public void write(byte[] b) throws IOException {
			synchronized (target) {
				target.write(b);
			}
		}

		@Override
		public void write(int b) throws IOException {
			synchronized (target) {
				target.write(b);
			}
		}
	}


	private IList runChecker(IRascalMonitor monitor, IList todoList, PathConfig pcfg)
			throws IOException, URISyntaxException {
	    if (!parallel || todoList.size() <= 10 || parallelAmount() <= 1) {
	    	getLog().debug("Running checker in single threaded mode");
	        return runCheckerSingle(monitor, todoList, makeEvaluator(System.err, System.out), pcfg);
		}
		ConcurrentSoftReferenceObjectPool<Evaluator> evaluators = createEvaluatorPool();

		Queue<IList> errorMessages = new ConcurrentLinkedQueue<>();
		IListWriter start = VF.listWriter();
		List<IList> chunks = splitTodoList(todoList, parallelPreChecks, start);

		AtomicReference<Exception> failure = new AtomicReference<>(null);
		ExecutorService executor = Executors.newFixedThreadPool(parallelAmount());
		try {
			IList initialTodo = start.done();
			Semaphore prePhaseDone = new Semaphore(0);
			if (!initialTodo.isEmpty()) {
				executor.execute(() -> {
					try {
						safeLog(l -> l.debug("Running pre-phase: " + initialTodo));
						errorMessages.add(evaluators.useAndReturn(eval ->
							runCheckerSingle(monitor, initialTodo, eval, pcfg)
						));
						safeLog(l -> l.debug("Finished running the pre-phase checker"));
					} catch (Exception e) {
						safeLog(l -> l.error("Failure executing pre-phase:", e));
						failure.compareAndSet(null, e);
					} finally {
						prePhaseDone.release(chunks.size() + 1);
					}
				});
				Thread.sleep(100); // give executor time to startup and avoid race
			}
			else {
				prePhaseDone.release(chunks.size() + 1);
			}
			if (!chunks.isEmpty()) {
				safeLog(l -> l.debug("Preparing checker for in " + chunks.size() + " parallel threads"));
				try {
					List<ISourceLocation> binFolders = new ArrayList<>();
					Semaphore done = new Semaphore(0);
					for (IList todo: chunks) {
						ISourceLocation myBin = VF.sourceLocation("tmp", "","tmp-" + System.identityHashCode(todo) + "-" + Instant.now().getEpochSecond());
						binFolders.add(myBin);
						PathConfig myConfig = new PathConfig(pcfg.getSrcs(), pcfg.getLibs().append(pcfg.getBin()), myBin);
						executor.execute(() -> {
							try {
								Thread.sleep(1000);  // give the other evaluator a head start
								safeLog(l -> l.debug("Starting fresh evaluator"));
								errorMessages.add(evaluators.useAndReturn(e -> {
									try {
										prePhaseDone.acquire();
										safeLog(l -> l.debug("Starting checking chunk with " + todo.size() +  " entries"));
										return runCheckerSingle(monitor, todo, e, myConfig);
									} catch (InterruptedException interruptedException) {
									    return VF.list();
									}
								}));
							} catch (Exception e) {
								safeLog(l -> l.error("Failure executing:", e));
								failure.compareAndSet(null, e);
							} finally {
								done.release();
							}
						});
					}
					done.acquire(chunks.size());
					// now have to merge the result bins
					mergeBinFolders(pcfg.getBin(), binFolders);

				} catch (URISyntaxException | IOException | InterruptedException e) {
					getLog().error("Failed to start the nested evaluator", e);
				}
			}
			prePhaseDone.acquire();
		} catch (InterruptedException e) {
		    // ingore
		} finally {
			executor.shutdown();
		}
		Exception failed = failure.get();
		if (failed != null) {
			throw new RuntimeException("One of the checkers failed", failed);
		}
		IListWriter result = VF.listWriter();
		for (IList err : errorMessages) {
			result.appendAll(err);
		}
		return result.done();
	}

	private ConcurrentSoftReferenceObjectPool<Evaluator> createEvaluatorPool() {
		return new ConcurrentSoftReferenceObjectPool<>(
				1, TimeUnit.MINUTES,
				1, parallelAmount(),
				() -> {
					try {
						return makeEvaluator(
								new SynchronizedOutputStream(System.err),
								new SynchronizedOutputStream(System.out)
						);
					} catch (URISyntaxException | IOException e) {
						throw new RuntimeException(e);
					}
		});
	}

	private static void mergeBinFolders(ISourceLocation bin, List<ISourceLocation> binFolders) throws IOException {
		for (ISourceLocation b : binFolders) {
			mergeBinFolders(bin, b);
		}
	}
	private static void mergeBinFolders(ISourceLocation dst, ISourceLocation src) throws IOException {
		for (String entry : reg.listEntries(src)) {
			ISourceLocation srcEntry = URIUtil.getChildLocation(src, entry);
			ISourceLocation dstEntry = URIUtil.getChildLocation(dst, entry);
			if (reg.isDirectory(srcEntry)) {
				if (!reg.exists(dstEntry)) {
					reg.mkDirectory(dstEntry);
				}
				mergeBinFolders(dstEntry, srcEntry);
			}
			else if (!reg.exists(dstEntry)) {
				reg.copy(srcEntry, dstEntry);
			}
			try {
				reg.remove(srcEntry); // cleanup the temp directory
			}
			catch (Exception e) {
				// IGNORE
			}
		}
    }



	private IList runCheckerSingle(IRascalMonitor monitor, IList todoList, IEvaluator<Result<IValue>> eval, PathConfig pcfg) {
		return (IList) eval.call(monitor, "check", todoList, pcfg.asConstructor());
	}

	private List<IList> splitTodoList(IList todoList, List<String> parallelPreList, IListWriter start) {
		Set<ISourceLocation> reserved = parallelPreList.stream().map(CompileRascalMojo::location).collect(Collectors.toSet());
		start.appendAll(reserved);
		int chunkSize = todoList.size() / parallelAmount();
		if (chunkSize < 10) {
			chunkSize = 10;
		}
		int currentChunkSize = 0;
		IListWriter currentChunk = VF.listWriter();
		List<IList> result = new ArrayList<>();
		for (IValue todo : todoList) {
			ISourceLocation module = (ISourceLocation) todo;
			if (!reserved.contains(module)) {
				currentChunk.append(module);
				if (currentChunkSize++ > chunkSize) {
					result.add(currentChunk.done());
					currentChunkSize = 0;
					currentChunk = VF.listWriter();
				}
			}
		}
		if (currentChunkSize > 0) {
			result.add(currentChunk.done());
		}
		return result;
	}

	private void collectDependentArtifactLibraries(List<ISourceLocation> libLocs) throws URISyntaxException, IOException {
	    RascalManifest mf = new RascalManifest();
	    Set<String> projects = libLocs.stream().map(l -> mf.getProjectName(l)).collect(Collectors.toSet());
	    
		for (Object o : project.getArtifacts()) {
			Artifact a = (Artifact) o;
			File file = a.getFile().getAbsoluteFile();
			ISourceLocation jarLoc = RascalManifest.jarify(location(file.toString()));
			
			if (reg.exists(URIUtil.getChildLocation(jarLoc, "META-INF/RASCAL.MF"))) {
			    String projectName = mf.getProjectName(jarLoc);
			    
			    // only add a library if it is not already on the lib path
			    if (!projects.contains(projectName)) {
			        libLocs.add(jarLoc);
			        projects.add(projectName);
			    }
			}
		}
	}

	private IList getTodoList(ISourceLocation binLoc, List<ISourceLocation> srcLocs, List<ISourceLocation> ignoredLocs)
			throws InclusionScanException, URISyntaxException {
		StaleSourceScanner scanner = new StaleSourceScanner(100);
		scanner.addSourceMapping(new SuffixMapping(".rsc", ".tpl"));
		
		Set<File> staleSources = new HashSet<>();
		for (ISourceLocation src : srcLocs) {
			staleSources.addAll(scanner.getIncludedSources(new File(src.getURI()), new File(binLoc.getURI())));
		}
		
		IListWriter filteredStaleSources = ValueFactoryFactory.getValueFactory().listWriter();
		
		OUTER:for (File file : staleSources) {
			ISourceLocation loc = URIUtil.createFileLocation(file.getAbsolutePath());
			
			for (ISourceLocation iloc : ignoredLocs) {
				if (isIgnoredBy(iloc, loc)) {
					continue OUTER;
				}
			}
			
			filteredStaleSources.append(loc);
		}
		
		IList todoList = filteredStaleSources.done();
		return todoList;
	}
	
	private boolean isIgnoredBy(ISourceLocation prefix, ISourceLocation loc) {
		assert prefix.getScheme().equals("file");
		assert loc.getScheme().equals("file");
		
		String prefixPath = prefix.getPath();
		String locPath = loc.getPath();
		
		return locPath.startsWith(prefixPath);
	}

	private void handleMessages(PathConfig pcfg, IList moduleMessages) throws MojoExecutionException {
		int maxLine = 0;
		int maxColumn = 0;
		boolean hasErrors = false;

		for (IValue val : moduleMessages) {
			ISet messages =  (ISet) ((IConstructor) val).get("messages");
			
			for (IValue error : messages) {
				ISourceLocation loc = (ISourceLocation) ((IConstructor) error).get("at");
				if(loc.hasLineColumn()) {
					maxLine = Math.max(loc.getBeginLine(), maxLine);
					maxColumn = Math.max(loc.getBeginColumn(), maxColumn);
				} else {
					getLog().error("loc without line/column: " + loc);
				}
			}
		}


		int lineWidth = (int) Math.log10(maxLine + 1) + 1;
		int colWidth = (int) Math.log10(maxColumn + 1) + 1;

		for (IValue val : moduleMessages) {
			ISourceLocation module = (ISourceLocation) ((IConstructor) val).get("src");
			ISet messages =  (ISet) ((IConstructor) val).get("messages");

			if (!messages.isEmpty()) {
				getLog().info("Warnings and errors for " + module);
			}
			
			for (IValue error : messages) {
				IConstructor msg = (IConstructor) error;
				String type = msg.getName();
				boolean isError = type.equals("error");
				boolean isWarning = type.equals("warning");

				hasErrors |= isError || warningsAsErrors;
				
				ISourceLocation loc = (ISourceLocation) msg.get("at");
				int col = 0;
				int line = 0;
				if(loc.hasLineColumn()) {
					col = loc.getBeginColumn();
					line = loc.getBeginLine();
				}

				String output 
				= abbreviate(loc, pcfg) 
				+ ":" 
				+ String.format("%0" + lineWidth + "d", line)
				+ ":"
				+ String.format("%0" + colWidth + "d", col)
				+ ": "
				+ ((IString) msg.get("msg")).getValue();

				if (isError) {
					// align "[ERROR]" with "[WARNING]" by adding two spaces
					getLog().error("  " + output); 
				}
				else if (isWarning) {
					getLog().warn(output);
				}
				else {
					// align "[INFO]" with "[WARNING]" by adding three spaces
					getLog().info("   " + output);
				}
			}
		}

		if (hasErrors && !errorsAsWarnings) {
			throw new MojoExecutionException("Rascal compiler found compile-time errors");
		}
		
		return;
	}

	private static String abbreviate(ISourceLocation loc, PathConfig pcfg) {
		for (IValue src : pcfg.getSrcs()) {
			String path = ((ISourceLocation) src).getURI().getPath();

			if (loc.getPath().startsWith(path)) {
				return loc.getPath().substring(path.length()); 
			}
		}

		return loc.getPath();
	}

	private List<ISourceLocation> locations(List<String> files) throws URISyntaxException, FactTypeUseException, IOException {
		List<ISourceLocation> result = new ArrayList<ISourceLocation>(files.size());

		for (String f : files) {
			result.add(location(f));
		}

		return result;
	}

	private static ISourceLocation location(String file) {
		if (file.startsWith("|") && file.endsWith("|")) {
			try {
				return (ISourceLocation) new StandardTextReader().read(ValueFactoryFactory.getValueFactory(), new StringReader(file));
			} catch (IOException e) {
			    throw new RuntimeException(e);
			}
		}
		else {
			try {
				return URIUtil.createFileLocation(file);
			} catch (URISyntaxException e) {
				throw new RuntimeException(e);
			}
		}
	}
}
