package com.google.javascript.jscomp;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.GwtIncompatible;
import com.google.javascript.jscomp.graph.DiGraph;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;


/** Instrument branch coverage for javascript. */
@GwtIncompatible("FileInstrumentationData")
public class AdvancedCoverageInstrumentationCallback extends NodeTraversal.AbstractPostOrderCallback {
  private final AbstractCompiler compiler;
  private final Map<String, FileInstrumentationData> instrumentationData;

  private static final String BRANCH_ARRAY_NAME_PREFIX = "instrumentCode";

  /** Returns a string that can be used for the branch coverage data. */
  private static String createArrayName(NodeTraversal traversal) {
    return BRANCH_ARRAY_NAME_PREFIX;
  }

  private static String createFunctionName(NodeTraversal traversal) {
    return BRANCH_ARRAY_NAME_PREFIX;
  }

  public AdvancedCoverageInstrumentationCallback(
      AbstractCompiler compiler, Map<String, FileInstrumentationData> instrumentationData) {
    this.compiler = compiler;
    this.instrumentationData = instrumentationData;
  }

  @Override
  public void visit(NodeTraversal traversal, Node node, Node parent) {
    String fileName = traversal.getSourceName();

    // If origin of node is not from sourceFile, do not instrument. This typically occurs when
    // polyfill code is injected into the sourceFile AST and this check avoids instrumenting it. We
    // avoid instrumentation as this callback does not distinguish between sourceFile code and
    // injected code and can result in an error.
    if (!Objects.equals(fileName, node.getSourceFileName())) {
      return;
    }

    if (Objects.equals(node.getSourceFileName(), "InstrumentCode.js")){
      return;
    }

    if (node.isScript()) {
      if (instrumentationData.get(fileName) != null) {
        Node toAddTo =
            node.hasChildren() && node.getFirstChild().isModuleBody() ? node.getFirstChild() : node;
        // Add instrumentation code
       // toAddTo.addChildrenToFront(newHeaderNode(traversal, toAddTo).removeChildren());
        compiler.reportChangeToEnclosingScope(node);
        instrumentBranchCoverage(traversal, instrumentationData.get(fileName));
      }
    }

    if (node.isIf()) {
      ControlFlowGraph<Node> cfg = traversal.getControlFlowGraph();
      boolean hasDefaultBlock = false;
      for (DiGraph.DiGraphEdge<Node, ControlFlowGraph.Branch> outEdge : cfg.getOutEdges(node)) {
        if (outEdge.getValue() == ControlFlowGraph.Branch.ON_FALSE) {
          Node destination = outEdge.getDestination().getValue();
          if (destination != null
              && destination.isBlock()
              && destination.getParent() != null
              && destination.getParent().isIf()) {
            hasDefaultBlock = true;
          }
          break;
        }
      }
      if (!hasDefaultBlock) {
        addDefaultBlock(node);
      }
      if (!instrumentationData.containsKey(fileName)) {
        instrumentationData.put(
            fileName, new FileInstrumentationData(fileName, createArrayName(traversal)));
      }
      processBranchInfo(node, instrumentationData.get(fileName), getChildrenBlocks(node));
    } else if (NodeUtil.isLoopStructure(node)) {
      List<Node> blocks = getChildrenBlocks(node);
      ControlFlowGraph<Node> cfg = traversal.getControlFlowGraph();
      for (DiGraph.DiGraphEdge<Node, ControlFlowGraph.Branch> outEdge : cfg.getOutEdges(node)) {
        if (outEdge.getValue() == ControlFlowGraph.Branch.ON_FALSE) {
          Node destination = outEdge.getDestination().getValue();
          if (destination != null && destination.isBlock()) {
            blocks.add(destination);
          } else {
            Node exitBlock = IR.block();
            if (destination != null && destination.getParent().isBlock()) {
              // When the destination of an outEdge of the CFG is not null and the source node's
              // parent is a block. If parent is not a block it may result in an illegal state
              // exception
              destination.getParent().addChildBefore(exitBlock, destination);
            } else {
              // When the destination of an outEdge of the CFG is null then we need to add an empty
              // block directly after the loop structure that we can later instrument
              outEdge
                  .getSource()
                  .getValue()
                  .getParent()
                  .addChildAfter(exitBlock, outEdge.getSource().getValue());
            }
            blocks.add(exitBlock);
          }
        }
      }
      if (!instrumentationData.containsKey(fileName)) {
        instrumentationData.put(
            fileName, new FileInstrumentationData(fileName, createArrayName(traversal)));
      }
      processBranchInfo(node, instrumentationData.get(fileName), blocks);
    }
  }

  private List<Node> getChildrenBlocks(Node node) {
    List<Node> blocks = new ArrayList<>();
    for (Node child : node.children()) {
      if (child.isBlock()) {
        blocks.add(child);
      }
    }
    return blocks;
  }

  /**
   * Add instrumentation code for branch coverage. For each block that correspond to a branch,
   * insert an assignment of the branch coverage data to the front of the block.
   */
  private void instrumentBranchCoverage(NodeTraversal traversal, FileInstrumentationData data) {
    int maxLine = data.maxBranchPresentLine();
    //int branchCoverageOffset = 0;
    for (int lineIdx = 1; lineIdx <= maxLine; ++lineIdx) {
      Integer numBranches = data.getNumBranches(lineIdx);
      if (numBranches != null) {
        for (int branchIdx = 1; branchIdx <= numBranches; ++branchIdx) {
          Node block = data.getBranchNode(lineIdx, branchIdx);
          block.addChildToFront(
              newBranchInstrumentationNode(traversal, block, lineIdx));
          compiler.reportChangeToEnclosingScope(block);
        }
      //  branchCoverageOffset += numBranches;
      }
    }
  }

  /**
   * Create an assignment to the branch coverage data for the given index into the array.
   *
   * @return the newly constructed assignment node.
   */
  private Node newBranchInstrumentationNode(NodeTraversal traversal, Node node, int lineIndex) {

    String arrayName = createArrayName(traversal);

    String functionName = createFunctionName(traversal);

    Node prop = IR.getprop(IR.name("InstrumentCode"), IR.string(functionName));
    Node functionCall = IR.call(prop, IR.string(traversal.getSourceName()), IR.string("foo"),IR.string("InstrumentCode.Type.BRANCH"), IR.number( lineIndex));
    Node exprNode = IR.exprResult(functionCall);

    // Note line as instrumented
    String fileName = traversal.getSourceName();
    if (!instrumentationData.containsKey(fileName)) {
      instrumentationData.put(fileName, new FileInstrumentationData(fileName, arrayName));
    }
    return exprNode.useSourceInfoIfMissingFromForTree(node);
  }

  /** Add branch instrumentation information for each block. */
  private void processBranchInfo(Node branchNode, FileInstrumentationData data, List<Node> blocks) {
    int lineNumber = branchNode.getLineno();
    data.setBranchPresent(lineNumber);

    // Instrument for each block
    int numBranches = 0;
    for (Node child : blocks) {
      data.putBranchNode(lineNumber, numBranches + 1, child);
      numBranches++;
    }
    data.addBranches(lineNumber, numBranches);
  }

  /** Add a default block for conditional statements, e.g., If, Switch. */
  private Node addDefaultBlock(Node node) {
    Node defaultBlock = IR.block();
    node.addChildToBack(defaultBlock);
    return defaultBlock.useSourceInfoIfMissingFromForTree(node);
  }

}
