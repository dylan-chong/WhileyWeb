package com.whileylabs;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.http.*;
import org.apache.http.client.utils.*;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHttpEntityEnclosingRequest;
import org.apache.http.protocol.HttpContext;

import jwebkit.http.HttpMethodDispatchHandler;
import wybs.lang.SyntaxError;
import wybs.util.StdBuildRule;
import wybs.util.StdProject;
import wyc.Activator;
import wyc.builder.CompileTask;
import wyc.lang.WhileyFile;
import wyal.lang.WyalFile;
import wyfs.lang.Content;
import wyfs.lang.Path;
import wyfs.util.JarFileRoot;
import wyfs.util.Trie;
import wyfs.util.VirtualRoot;
import wyil.builders.Wyil2WyalBuilder;
import wyil.lang.WyilFile;
import wyjs.core.JavaScriptFile;
import wyjs.tasks.JavaScriptCompileTask;
import wytp.provers.AutomatedTheoremProver;
import wybs.lang.Attribute;

public class WhileyLabsCompiler extends HttpMethodDispatchHandler {
	private static String WYRT_LIB = "lib/wyrt-v0.1.1.jar".replace('/',File.separatorChar);

	public WhileyLabsCompiler() {
		super(HttpMethodDispatchHandler.ALLOW_POST);
	}

	@Override
	public void post(HttpRequest request, HttpResponse response, HttpContext context)
			throws HttpException, IOException {
		HttpEntity entity = (HttpEntity) checkHasEntity(request);
		List<NameValuePair> params = URLEncodedUtils.parse(entity);
		String code = null;
		boolean verification=false;
		for (NameValuePair p : params) {
			if (p.getName().equals("code")) {
				code = p.getValue();
			} else if(p.getName().equals("verify")) {
				verification = Boolean.parseBoolean(p.getValue());
			}
		}
		if (code == null) {
			response.setStatusCode(HttpStatus.SC_BAD_REQUEST);
		} else {
			String r = compile(code,verification);
			response.setEntity(new StringEntity(r)); // ContentType.APPLICATION_JSON fails?
			response.setStatusCode(HttpStatus.SC_OK);
		}
	}

	private HttpEntity checkHasEntity(HttpRequest request) throws HttpException {
		if (request instanceof BasicHttpEntityEnclosingRequest) {
			BasicHttpEntityEnclosingRequest r = (BasicHttpEntityEnclosingRequest) request;
			return r.getEntity();
		} else {
			throw new HttpException("Missing entity");
		}
	}

	private String compile(String code, boolean verification) throws IOException, HttpException {
		Path.ID mainID = Trie.ROOT.append("main");
		Content.Registry registry = new Activator.Registry();
		VirtualRoot root = new VirtualRoot(registry);
		// Configure root for standard library
		JarFileRoot stdlib = new JarFileRoot(WYRT_LIB,registry);
		Path.Entry<WhileyFile> srcFile = root.create(mainID, WhileyFile.ContentType);
		// Write contents into source file
		srcFile.outputStream().write(code.getBytes());
		// Create registry and initialise root with the source file
		ArrayList<Path.Root> roots = new ArrayList<>();
		roots.add(root);
		roots.add(stdlib);
		StdProject project = new StdProject(roots);
		addWhiley2WyilBuildRule(root,project);
		addWyil2JavaScriptBuildRule(root,project);
		if(verification) {
			addVerificationBuildRules(root,project);
		}
		// Create project
		HashMap<String, Object> result = new HashMap<>();
		List<Path.Entry<WhileyFile>> entries = new ArrayList<>();
		entries.add(srcFile);
		try {
			project.build(entries);
			Path.Entry<JavaScriptFile> file = project.get(mainID,JavaScriptFile.ContentType);
			result.put("result", "success");
			result.put("js", extractJavaScript(file));
		} catch (SyntaxError e) {
			Attribute.Source src = e.getElement().attribute(Attribute.Source.class);
			EnclosingLine enclosing = readEnclosingLine(srcFile.inputStream(), src.start, src.end);
			result.put("result", "errors");
			result.put("errors", toErrorResponse(enclosing, e.getMessage()));
		} catch (Exception e) {
			// now what?
			throw new RuntimeException(e);
		}

		return toJsonString(result);
	}

	/**
	 * Add the rule for compiling Whiley source files into WyIL files.
	 *
	 * @param project
	 */
	protected void addWhiley2WyilBuildRule(Path.Root root, StdProject project) {
		// Configure build rules for normal compilation
		Content.Filter<WhileyFile> whileyIncludes = Content.filter("**", WhileyFile.ContentType);
		Content.Filter<WhileyFile> whileyExcludes = null;
		// Rule for compiling Whiley to WyIL
		CompileTask wyilBuilder = new CompileTask(project);
		//wyilBuilder.setLogger(logger);
		project.add(new StdBuildRule(wyilBuilder, root, whileyIncludes, whileyExcludes, root));
	}

	protected void addWyil2JavaScriptBuildRule(Path.Root root, StdProject project) {
		// Configure build rules for normal compilation
		Content.Filter<WyilFile> wyilIncludes = Content.filter("**", WyilFile.ContentType);
		Content.Filter<WyilFile> wyilExcludes = null;
		// Rule for compiling Whiley to WyIL
		JavaScriptCompileTask jsBuilder = new JavaScriptCompileTask(project);
		project.add(new StdBuildRule(jsBuilder, root, wyilIncludes, wyilExcludes, root));
	}

	/**
	 * Add build rules necessary for compiling wyil binary files into wyal files
	 * for verification.
	 *
	 * @param project
	 */
	protected void addVerificationBuildRules(Path.Root root, StdProject project) {
		// Configure build rules for verification (if applicable)
		Content.Filter<WyilFile> wyilIncludes = Content.filter("**", WyilFile.ContentType);
		Content.Filter<WyilFile> wyilExcludes = null;
		Content.Filter<WyalFile> wyalIncludes = Content.filter("**", WyalFile.ContentType);
		Content.Filter<WyalFile> wyalExcludes = null;
		// Rule for compiling WyIL to WyAL
		Wyil2WyalBuilder wyalBuilder = new Wyil2WyalBuilder(project);
		project.add(new StdBuildRule(wyalBuilder, root, wyilIncludes, wyilExcludes, root));
		//
		wytp.types.TypeSystem typeSystem = new wytp.types.TypeSystem(project);
		AutomatedTheoremProver prover = new AutomatedTheoremProver(typeSystem);
		wyal.tasks.CompileTask wyalBuildTask = new wyal.tasks.CompileTask(project,typeSystem,prover);
		wyalBuildTask.setVerify(true);
		project.add(new StdBuildRule(wyalBuildTask, root, wyalIncludes, wyalExcludes, root));
	}

	private static ArrayList toErrorResponse(EnclosingLine enclosing, String message) {
		ArrayList l = new ArrayList();
		HashMap<String, Object> args = new HashMap<>();
		args.put("filename", "main.whiley");
		args.put("line", enclosing.lineNumber);
		args.put("start", enclosing.columnStart());
		args.put("end", enclosing.columnEnd());
		args.put("text", message);
		args.put("context", Collections.EMPTY_LIST);
		l.add(args);
		return l;
	}

	private static String extractJavaScript(Path.Entry<JavaScriptFile> file) throws IOException {
		JavaScriptFile jsf = file.read();
		return jsf.toString();
	}

	/**
	 * This is a simple hack for converting Java objects into JSON. It needs to
	 * be replaced by a proper JSON library.
	 *
	 * @param o
	 * @return
	 */
	private static String toJsonString(Object o) {
		if (o instanceof String) {
			String r = (String) o;
			r = StringEscapeUtils.escapeJava(r);
			return "\"" + r + "\"";
		} else if (o instanceof Integer) {
			return o.toString();
		} else if (o instanceof List) {
			List l = (List) o;
			String r = "[";
			boolean firstTime = true;
			for (Object e : l) {
				if (!firstTime) {
					r += ",";
				}
				firstTime = false;
				r += toJsonString(e);
			}
			return r + "]";
		} else if (o instanceof Map) {
			Map<String, Object> l = (Map) o;
			String r = "{";
			boolean firstTime = true;
			for (Map.Entry<String, Object> e : l.entrySet()) {
				if (!firstTime) {
					r += ",";
				}
				firstTime = false;
				r += toJsonString(e.getKey());
				r += ": ";
				r += toJsonString(e.getValue());
			}
			return r + "}";
		} else {
			throw new IllegalArgumentException();
		}
	}

	private static EnclosingLine readEnclosingLine(InputStream input, int start, int end) {
		int line = 0;
		int lineStart = 0;
		int lineEnd = 0;
		StringBuilder text = new StringBuilder();
		try {
			BufferedReader in = new BufferedReader(new InputStreamReader(input));

			// first, read whole file
			int len = 0;
			char[] buf = new char[1024];
			while ((len = in.read(buf)) != -1) {
				text.append(buf, 0, len);
			}

			while (lineEnd < text.length() && lineEnd <= start) {
				lineStart = lineEnd;
				lineEnd = parseLine(text, lineEnd);
				line = line + 1;
			}
		} catch (IOException e) {
			return null;
		}
		lineEnd = Math.min(lineEnd, text.length());

		return new EnclosingLine(start, end, line, lineStart, lineEnd, text.substring(lineStart, lineEnd));
	}

	private static int parseLine(StringBuilder buf, int index) {
		while (index < buf.length() && buf.charAt(index) != '\n') {
			index++;
		}
		return index + 1;
	}

	private static class EnclosingLine {
		private int lineNumber;
		private int start;
		private int end;
		private int lineStart;
		private int lineEnd;
		private String lineText;

		public EnclosingLine(int start, int end, int lineNumber, int lineStart, int lineEnd, String lineText) {
			this.start = start;
			this.end = end;
			this.lineNumber = lineNumber;
			this.lineStart = lineStart;
			this.lineEnd = lineEnd;
			this.lineText = lineText;
		}

		public int columnStart() {
			return start - lineStart;
		}

		public int columnEnd() {
			return Math.min(end, lineEnd) - lineStart;
		}
	}
}
