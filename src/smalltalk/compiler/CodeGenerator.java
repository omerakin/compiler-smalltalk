package smalltalk.compiler;

import org.antlr.symtab.*;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;
import smalltalk.compiler.symbols.*;
import java.util.List;

/** Fill STBlock, STMethod objects in Symbol table with bytecode,
 * {@link STCompiledBlock}.
 */
public class CodeGenerator extends SmalltalkBaseVisitor<Code> {
	public static final boolean dumpCode = false;

	public STClass currentClassScope;
	public Scope currentScope;

	/** With which compiler are we generating code? */
	public final Compiler compiler;

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
			List<Scope> STBlocks = ctx.scope.getAllNestedScopedSymbols();
			ctx.scope.compiledBlock.blocks = new STCompiledBlock[STBlocks.size()];
			for(int i=0; i<STBlocks.size();i++){
				STBlock stb = ((STBlock)STBlocks.get(i));
				ctx.scope.compiledBlock.blocks[stb.index] = stb.compiledBlock;
			}
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

	public STCompiledBlock getCompiledBlock(STBlock stBlock) {
		STCompiledBlock compiledBlock = new STCompiledBlock(currentClassScope, stBlock);
		return compiledBlock;
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
		for (int i=0; i< stats.size(); i++) {
			code =code.join(visit(ctx.stat(i)));
			if (i < stats.size()-1) {
				code = code.join(Compiler.pop());
			}
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
		List<Scope> STBlocks = methodContext.scope.getAllNestedScopedSymbols();
		methodContext.scope.compiledBlock.blocks = new STCompiledBlock[STBlocks.size()];
		for(int i=0; i<STBlocks.size();i++){
			STBlock stb = ((STBlock)STBlocks.get(i));
			methodContext.scope.compiledBlock.blocks[stb.index] = stb.compiledBlock;
		}
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
		return code;
	}

	@Override
	public Code visitKeywordSend(SmalltalkParser.KeywordSendContext ctx) {
		Code code = visit(ctx.recv);
		Code args = new Code();
		for (SmalltalkParser.BinaryExpressionContext binaryExpressionContext: ctx.args) {
			args.join(visit(binaryExpressionContext));
		}
		String s = "";
		for (TerminalNode terminalNode: ctx.KEYWORD()) {
			s += terminalNode.getText();
		}
		int literalIndex = getLiteralIndex(s);
		int size = ctx.args.size();
		args.join(Compiler.send(size, literalIndex));
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
				int literalIndex = getLiteralIndex(operand);
				code = aggregateResult(code, Compiler.send(1,literalIndex));
			}
		}
		return code;
	}

	@Override
	public Code visitBlock(SmalltalkParser.BlockContext ctx) {
		pushScope(ctx.scope);
		Code icode = new Code();
		icode.join(Compiler.block((short) ctx.scope.index));
		Code code = visitChildren(ctx);
		if (ctx.body() instanceof SmalltalkParser.EmptyBodyContext){
			code = code.join(Compiler.push_nil());
		}
		code = code.join(Compiler.block_return());
		ctx.scope.compiledBlock = getCompiledBlock(ctx.scope);
		ctx.scope.compiledBlock.bytecode = code.bytes();
		popScope();
		return icode;
	}

	@Override
	public Code visitPrimitiveMethodBlock(SmalltalkParser.PrimitiveMethodBlockContext ctx) {
		SmalltalkParser.MethodContext methodNode = (SmalltalkParser.MethodContext)ctx.getParent();
		pushScope(methodNode.scope);
		Code code = visitChildren(ctx);
		methodNode.scope.compiledBlock = getCompiledMethod(methodNode.scope);
		popScope();
		return code;
	}

	@Override
	public Code visitUnaryMsgSend(SmalltalkParser.UnaryMsgSendContext ctx) {
		Code code = visit(ctx.unaryExpression());
		String s = ctx.ID().getText();
		int literalIndex = getLiteralIndex(s);
		code.join(Compiler.send(0,literalIndex));
		return code;
	}

	@Override
	public Code visitUnarySuperMsgSend(SmalltalkParser.UnarySuperMsgSendContext ctx) {
		Code code = new Code();
		String str = ctx.ID().getText();
		int index = getLiteralIndex(str);
		code.join(Compiler.push_self()).join(Compiler.send_super(0, index));
		return code;
	}

	@Override
	public Code visitId(SmalltalkParser.IdContext ctx) {
		Code code = new Code();
		code.join(push(ctx.getText()));
		return code;
	}

	@Override
	public Code visitLiteral(SmalltalkParser.LiteralContext ctx) {
		Code code = new Code();
		if (ctx.NUMBER() != null) {
			String number = ctx.NUMBER().getText();
			if (number.contains(".")) {
				float aFloat = Float.parseFloat(number);
				code.join(Compiler.push_float(aFloat));
			} else {
				int i = Integer.parseInt(number);
				code.join(Compiler.push_int(i));
			}
		} else if (ctx.CHAR() != null) {
			char c = ctx.CHAR().getText().charAt(1);
			code.join(Compiler.push_char(c));
		} else if (ctx.STRING() != null) {
			String s = ctx.STRING().getText();
			int literalIndex = getLiteralIndex(s);
			code.join(Compiler.push_literal(literalIndex));
		} else {
			String s = ctx.getText();
			switch (s) {
				case "nil":
					code.join(Compiler.push_nil());
					break;
				case "self":
					code.join(Compiler.push_self());
					break;
				case "true":
					code.join(Compiler.push_true());
					break;
				case "false":
					code.join(Compiler.push_false());
					break;
				default:
					break;
			}
		}
		return code;
	}

	@Override
	public Code visitReturn(SmalltalkParser.ReturnContext ctx) {
		Code e = visit(ctx.messageExpression());
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

	public int getLiteralIndex(String operand) {
		return currentClassScope.stringTable.add(operand.replace("\'",""));
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
		if (symbol instanceof STField) {
			code.join(Compiler.store_field(symbol.getInsertionOrderNumber())); ////////////////
		} else if (symbol instanceof STVariable) {
			int in = symbol.getInsertionOrderNumber();
			int sc = ((STBlock)currentScope).getRelativeScopeCount(symbol.getScope().getName());
			code.join(Compiler.store_local(sc, in));
		}
		return code;
	}

	public Code push(String id) {
		Code code = new Code();
		Symbol symbol = currentScope.resolve(id);
		if (symbol == null || symbol.getScope() == compiler.symtab.GLOBALS) {
			int literalIndex = getLiteralIndex(id);
			code.join(Compiler.push_global(literalIndex));
		} else {
			if (symbol instanceof STField) {
				if (((STClass) symbol.getScope()).getSuperClassScope() != null) {
					int numberOfFields = currentClassScope.getSuperClassScope().getNumberOfFields();
					code.join(Compiler.push_field(symbol.getInsertionOrderNumber()+numberOfFields));
				} else {
					code.join(Compiler.push_field(symbol.getInsertionOrderNumber()));
				}
			} else {
				int in = symbol.getInsertionOrderNumber();
				int sc = ((STBlock) currentScope).getRelativeScopeCount(symbol.getScope().getName());
				code.join(Compiler.push_local(sc, in));
			}
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
