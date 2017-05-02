package smalltalk.compiler;

import org.antlr.symtab.*;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;
import smalltalk.compiler.symbols.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Fill STBlock, STMethod objects in Symbol table with bytecode,
 * {@link STCompiledBlock}.
 */
public class CodeGenerator extends SmalltalkBaseVisitor<Code> {
	public static final boolean dumpCode = false;

	public STClass currentClassScope;
	public Scope currentScope;

	/** With which compiler are we generating code? */
	public final Compiler compiler;

	//keep record of scope (block) and its strings
	public final Map<Scope, StringTable> scopeStringTableMap = new HashMap<>();

	public CodeGenerator(Compiler compiler) {
		this.compiler = compiler;
	}

	/** This and defaultResult() critical to getting code to bubble up the
	 *  visitor call stack when we don't implement every method.
	 */
	@Override
	protected Code aggregateResult(Code aggregate, Code nextResult) {
		if ( aggregate!=Code.None ) {
			if ( nextResult!=Code.None ) {
				return aggregate.join(nextResult);
			}
			return aggregate;
		}
		else {
			return nextResult;
		}
	}

	@Override
	protected Code defaultResult() {
		return Code.None;
	}

	@Override
	public Code visitFile(SmalltalkParser.FileContext ctx) {
		currentScope = compiler.symtab.GLOBALS;
		visitChildren(ctx);
		return Code.None;
	}

	@Override
	public Code visitMain(SmalltalkParser.MainContext ctx) {
		Code code = null;
		if (ctx.scope != null) {
			currentClassScope = ctx.classScope;
			pushScope(ctx.classScope);
			pushScope(ctx.scope);

			code = visitChildren(ctx);
			code = code.join(Compiler.pop()); // final value
			code = code.join(Compiler.push_self()); //always add ^self
			code = code.join(Compiler.method_return());
			ctx.scope.compiledBlock = getCompiledMethod(ctx.scope);
			ctx.scope.compiledBlock.bytecode = code.bytes();

			popScope();
			popScope();
			currentClassScope = null;
		}
		return code;
	}

	@Override
	public Code visitClassDef(SmalltalkParser.ClassDefContext ctx) {
		currentClassScope = ctx.scope;
		pushScope(ctx.scope);
		visitChildren(ctx);
		popScope();
		currentClassScope = null;
		return Code.None;
	}

	public STCompiledBlock getCompiledPrimitive(STPrimitiveMethod primitive) {
		STCompiledBlock compiledMethod = new STCompiledBlock(currentClassScope, primitive);
		return compiledMethod;
	}

	public STCompiledBlock getCompiledMethod (STMethod method) {
		STCompiledBlock compiledMethod = new STCompiledBlock(currentClassScope, method);
		return compiledMethod;
	}

	/*
	All expressions have values. Must pop each expression value off, except
	last one, which is the block return value. So, we pop after each expr
	unless we're compiling a method block and the expr is not a ^expr. In a
	code block, we pop if we're not the last instruction of the block.

	localVars? expr ('.' expr)* '.'?
	 */
	@Override
	public Code visitFullBody(SmalltalkParser.FullBodyContext ctx) {
		Code code = new Code();

		List<SmalltalkParser.StatContext> stats = ctx.stat();
		for (SmalltalkParser.StatContext stat: stats) {
			code =code.join(visit(stat));
		}
		return code;
	}

	@Override
	public Code visitSmalltalkMethodBlock(SmalltalkParser.SmalltalkMethodBlockContext ctx) {
		SmalltalkParser.MethodContext methodContext = (SmalltalkParser.MethodContext) ctx.getParent();
		pushScope(((SmalltalkParser.MethodContext) ctx.getParent()).scope);

		Code code = visitChildren(ctx);
		if (ctx.body() instanceof SmalltalkParser.FullBodyContext) {
			code = code.join(Compiler.pop());
		}
		code = code.join(Compiler.push_self());
		code = code.join(Compiler.method_return());
		methodContext.scope.compiledBlock = getCompiledMethod(methodContext.scope);
		methodContext.scope.compiledBlock.bytecode = code.bytes();
		popScope();
		return Code.None;
	}

	@Override
	public Code visitAssign(SmalltalkParser.AssignContext ctx) {
		Code rigthside = visit(ctx.messageExpression());
		Code leftside = store(ctx.lvalue().ID().getText());
		Code code = rigthside.join(leftside);

		return code;
	}

	@Override
	public Code visitPassThrough(SmalltalkParser.PassThroughContext ctx) {
		Code code = visit(ctx.recv);
		Code args = new Code();

		aggregateResult(code, args);

		return code;
	}

	@Override
	public Code visitBinaryExpression(SmalltalkParser.BinaryExpressionContext ctx) {

		Code code = visit(ctx.unaryExpression(0));

		if (ctx.bop().size() != 0) {
			String operand;
			for (int i=1; i<=ctx.bop().size(); i++) {
				code = aggregateResult(code, visit(ctx.unaryExpression(i)));
				operand = ctx.bop().get(i-1).getText();
				if (operand.contains("'")) {
					operand = operand.replaceAll("'", "");
				} else if (scopeStringTableMap.get(currentScope) != null) {
					scopeStringTableMap.get(currentScope).add(operand);
				} else {
					StringTable stringTable = new StringTable();
					stringTable.add(operand);
					scopeStringTableMap.put(currentScope, stringTable);
				}
				int literalIndex = -1;
				if (scopeStringTableMap.containsKey(currentScope)) {
					StringTable table = scopeStringTableMap.get(currentScope);
					String[] strings = table.toArray();
					for (int j=0; j<strings.length; j++) {
						if (strings[j].equals(operand) || operand.equals("'" + strings[j] + "'")) {
							literalIndex = j;
						}
					}
				}
				code = aggregateResult(code, Compiler.send(1,literalIndex));//////
			}
		}
		return code;
	}

	@Override
	public Code visitId(SmalltalkParser.IdContext ctx) {
		Code code = new Code();
		code.join(push(ctx.getText()));

		return code;
	}

	@Override
	public Code visitReturn(SmalltalkParser.ReturnContext ctx) {
		Code e = visit(ctx.messageExpression());
		if ( compiler.genDbg ) {
			e = Code.join(e, dbg(ctx.start)); // put dbg after expression as that is when it executes
		}
		Code code = e.join(Compiler.method_return());
		return code;
	}

	public void pushScope(Scope scope) {
		currentScope = scope;
	}

	public void popScope() {
//		if ( currentScope.getEnclosingScope()!=null ) {
//			System.out.println("popping from " + currentScope.getScopeName() + " to " + currentScope.getEnclosingScope().getScopeName());
//		}
//		else {
//			System.out.println("popping from " + currentScope.getScopeName() + " to null");
//		}
		currentScope = currentScope.getEnclosingScope();
	}

	public int getLiteralIndex(String s) {
		return 0;
	}

	public Code dbgAtEndMain(Token t) {
		int charPos = t.getCharPositionInLine() + t.getText().length();
		return dbg(t.getLine(), charPos);
	}

	public Code dbgAtEndBlock(Token t) {
		int charPos = t.getCharPositionInLine() + t.getText().length();
		charPos -= 1; // point at ']'
		return dbg(t.getLine(), charPos);
	}

	public Code dbg(Token t) {
		return dbg(t.getLine(), t.getCharPositionInLine());
	}

	public Code dbg(int line, int charPos) {
		return Compiler.dbg(getLiteralIndex(compiler.getFileName()), line, charPos);
	}

	public Code store(String id) {
		Code code = new Code();
		Symbol symbol = currentScope.resolve(id);
		if (symbol instanceof STVariable) {
			int in = symbol.getInsertionOrderNumber();
			int sc = ((STBlock)currentScope).getRelativeScopeCount(symbol.getScope().getName());
			code.join(Compiler.store_local(sc, in));
		}

		return code;
	}

	public Code push(String id) {
		Code code = new Code();
		Symbol symbol = currentScope.resolve(id);

		if (symbol instanceof STField) {
			code.join(Compiler.push_field(symbol.getInsertionOrderNumber()));
		} else {
			int in = symbol.getInsertionOrderNumber();
			int sc = ((STBlock) currentScope).getRelativeScopeCount(symbol.getScope().getName());
			code.join(Compiler.push_local(sc, in));
		}

		return code;
	}

	public Code sendKeywordMsg(ParserRuleContext receiver,
							   Code receiverCode,
							   List<SmalltalkParser.BinaryExpressionContext> args,
							   List<TerminalNode> keywords)
	{
		return null;
	}

	public String getProgramSourceForSubtree(ParserRuleContext ctx) {
		return null;
	}
}
