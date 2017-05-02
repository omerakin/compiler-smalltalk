package smalltalk.compiler;

import org.antlr.symtab.Scope;
import org.antlr.symtab.VariableSymbol;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import smalltalk.compiler.misc.Utils;
import smalltalk.compiler.symbols.STArg;
import smalltalk.compiler.symbols.STBlock;
import smalltalk.compiler.symbols.STClass;
import smalltalk.compiler.symbols.STField;
import smalltalk.compiler.symbols.STMethod;
import smalltalk.compiler.symbols.STPrimitiveMethod;
import smalltalk.compiler.symbols.STSymbolTable;
import smalltalk.compiler.symbols.STVariable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

public class Compiler {
	protected STSymbolTable symtab;
	protected SmalltalkParser parser;
	protected CommonTokenStream tokens;
	protected SmalltalkParser.FileContext fileTree;
	protected String fileName;
	public boolean genDbg; // generate dbg file,line instructions

	public final List<String> errors = new ArrayList<>();

	public Compiler() {
		symtab = new STSymbolTable();
	}

	public Compiler(STSymbolTable symtab) {
		this.symtab = symtab;
	}

	public STSymbolTable compile(String fileName, String input) {

		ParserRuleContext tree = parseClasses(new ANTLRInputStream(input));
		defSymbols(tree);
		resolveSymbols(tree);
		CodeGenerator codeGenerator = new CodeGenerator(this);
		codeGenerator.visit(tree);


		return symtab;
	}

	/** Parse classes and/or a chunk of code, returning AST root.
	 *  Return null upon syntax error.
	 */
	public ParserRuleContext parseClasses(CharStream input) {
		SmalltalkLexer l = new SmalltalkLexer(input);
		CommonTokenStream tokens = new CommonTokenStream(l);
		//System.out.println(tokens.getTokens());

		this.parser = new SmalltalkParser(tokens);
		fileTree= parser.file();

		//System.out.println(((Tree)r.getTree()).toStringTree());
		if ( parser.getNumberOfSyntaxErrors()>0 ) return null;
		return fileTree;
	}

	public void defSymbols(ParserRuleContext tree) {
		// Define classes/fields in first pass over tree
		// This allows us to have forward class references
		DefineSymbols def = new DefineSymbols(this);
		ParseTreeWalker walker = new ParseTreeWalker();
		walker.walk(def, tree);
	}

	public void resolveSymbols(ParserRuleContext tree) {
		ResolveSymbols def = new ResolveSymbols(this);
		ParseTreeWalker walker = new ParseTreeWalker();
		walker.walk(def, tree);
	}

	public STBlock createBlock(STMethod currentMethod, ParserRuleContext tree) {
//		System.out.println("create block in "+currentMethod+" "+args);
		return new STBlock(currentMethod, tree);
	}

	public STMethod createMethod(String selector, ParserRuleContext tree) {
//		System.out.println("	create method "+selector+" "+args);
		return new STMethod(selector, tree);
	}

	public STPrimitiveMethod createPrimitiveMethod(STClass currentClass,
	                                               String selector,
	                                               String primitiveName,
	                                               SmalltalkParser.MethodContext tree)
	{
//		System.out.println("	create primitive "+selector+" "+args+"->"+primitiveName);
		// convert "<classname>_<methodname>" Primitive value
		// warn if classname!=currentClass
		return null;
	}

	public void defineVariables(Scope scope, List<String> names, Function<String,? extends VariableSymbol> getter) {
		if ( names!=null ) {
			for (String name : names) {
				VariableSymbol v = getter.apply(name);
				if ( scope.getSymbol(v.getName())!=null ) {
					error("redefinition of "+v.getName()+" in "+scope.toQualifierString(">>"));
				}
				else {
					scope.define(v);
				}
			}
		}
	}

	public void defineFields(Scope scope, List<String> names) {
		defineVariables(scope, names, n -> new STField(n));
	}

	public void defineArguments(Scope scope, List<String> names) {
		defineVariables(scope, names, n -> new STArg(n));
	}

	public void defineLocals(Scope scope, List<String> names) {
		defineVariables(scope, names, n -> new STVariable(n));
	}

	// Convenience methods for code gen

	public static Code push_nil() 				{ return Code.of(Bytecode.NIL); }
	public static Code push_self()				{ return Code.of(Bytecode.SELF); }
	public static Code push_field(int v)		{ return Code.of(Bytecode.PUSH_FIELD).join(Utils.toLiteral(v)); }
	public static Code push_local(int s, int i)	{ return Code.of(Bytecode.PUSH_LOCAL).join(Utils.toLiteral(s).join(Utils.toLiteral(i))); }

	public static Code store_local(int s, int i){ return Code.of(Bytecode.STORE_LOCAL).join(Utils.shortToBytes(s).join(Utils.shortToBytes(i))); }
	public static Code pop()					{ return Code.of(Bytecode.POP); }
	public static Code send(int size, int i)	{ return Code.of(Bytecode.SEND).join(Utils.toLiteral(size).join(Utils.toLiteral(i))); }

	public static Code method_return()          { return Code.of(Bytecode.RETURN); }

	public static Code dbg(int filenameLitIndex, int line, int charPos) {
		return null;
	}

	public String getFileName() {
		return fileName;
	}

	public void setFileName(String fileName) {
		this.fileName = fileName;
	}

	// Error support

	public void error(String msg) {
		errors.add(msg);
	}

	public void error(String msg, Exception e) {
		errors.add(msg+"\n"+ Arrays.toString(e.getStackTrace()));
	}
}
