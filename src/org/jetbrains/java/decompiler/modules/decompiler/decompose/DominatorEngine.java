// Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.java.decompiler.modules.decompiler.decompose;

import org.jetbrains.java.decompiler.modules.decompiler.StatEdge;
import org.jetbrains.java.decompiler.modules.decompiler.stats.Statement;
import org.jetbrains.java.decompiler.util.collections.VBStyleCollection;

import java.util.*;

public class DominatorEngine {

  private final Statement statement;

  private final VBStyleCollection<Integer, Integer> colOrderedIDoms = new VBStyleCollection<>();
  private final Set<Integer> rootIds = new LinkedHashSet<>();


  public DominatorEngine(Statement statement) {
    this.statement = statement;
  }

  public void initialize() {
    calcIDoms();
  }

  private void orderStatements() {
    colOrderedIDoms.clear();

    Set<Integer> universe = new LinkedHashSet<>();
    for (Statement stat : statement.getStats()) {
      universe.add(stat.id);
    }

    Integer firstId = statement.getFirst().id;
    if (universe.contains(firstId)) {
      colOrderedIDoms.addWithKey(null, firstId);
    }

    for (Statement stat : statement.getReversePostOrderList()) {
      if (universe.contains(stat.id) && !colOrderedIDoms.containsKey(stat.id)) {
        colOrderedIDoms.addWithKey(null, stat.id);
      }
    }

    for (Statement stat : statement.getStats()) {
      if (!colOrderedIDoms.containsKey(stat.id)) {
        colOrderedIDoms.addWithKey(null, stat.id);
      }
    }
  }

  private static Integer getCommonIDom(Integer key1, Integer key2, VBStyleCollection<Integer, Integer> orderedIDoms) {
    Integer oldKey;

    if (key1 == null) {
      return key2;
    }
    else if (key2 == null) {
      return key1;
    }

    int index1 = orderedIDoms.getIndexByKey(key1);
    int index2 = orderedIDoms.getIndexByKey(key2);

    while (index1 != index2) {
      if (index1 > index2) {
        oldKey = key1;
        key1 = orderedIDoms.getWithKey(key1);
        if (key1 == null || key1.equals(oldKey)) {
          return null;
        }
        index1 = orderedIDoms.getIndexByKey(key1);
      }
      else {
        oldKey = key2;
        key2 = orderedIDoms.getWithKey(key2);
        if (key2 == null || key2.equals(oldKey)) {
          return null;
        }
        index2 = orderedIDoms.getIndexByKey(key2);
      }
    }

    return key1;
  }

  private void calcIDoms() {

    orderStatements();
    rootIds.clear();

    Integer firstId = statement.getFirst().id;
    colOrderedIDoms.putWithKey(firstId, firstId);
    rootIds.add(firstId);

    // exclude first statement
    List<Integer> lstIds = colOrderedIDoms.getLstKeys().subList(1, colOrderedIDoms.getLstKeys().size());

    while (true) {

      boolean changed = false;

      for (int id : lstIds) {

        Statement stat = statement.getStats().getWithKey(id);
        if (stat == null) {
          continue;
        }
        Integer idom = null;

        for (StatEdge edge : stat.getAllPredecessorEdges()) {
          if (colOrderedIDoms.getWithKey(edge.getSource().id) != null) {
            idom = getCommonIDom(idom, edge.getSource().id, colOrderedIDoms);
            if (idom == null) {
              break;
            }
          }
        }

        if (idom == null) {
          idom = id;
          rootIds.add(id);
        }
        else {
          rootIds.remove(id);
        }

        Integer oldidom = colOrderedIDoms.putWithKey(idom, id);
        if (!idom.equals(oldidom)) {
          changed = true;
        }
      }

      if (!changed) {
        break;
      }
    }
  }

  public VBStyleCollection<Integer, Integer> getOrderedIDoms() {
    return colOrderedIDoms;
  }

  public Set<Integer> getRootIds() {
    return new LinkedHashSet<>(rootIds);
  }

  // Returns if 'node' is dominated by 'dom'
  // aka if 'dom' is a dominator of 'node'
  public boolean isDominator(Integer node, Integer dom) {
    while (!node.equals(dom)) {

      Integer idom = colOrderedIDoms.getWithKey(node);

      if (idom.equals(node)) {
        return false; // root node
      } else {
        node = idom;
      }
    }

    return true;
  }

  // Find all nodes dominated by the start node
  public Set<Integer> allDomsFor(Integer start) {
    Set<Integer> ret = new HashSet<>();

    Deque<Integer> stack = new LinkedList<>();
    stack.add(start);

    while (!stack.isEmpty()) {
      Integer id = stack.removeFirst();

      if (ret.contains(id)) {
        continue;
      }

      ret.add(id);

      // Find every node that equals the current in the set, then add those keys onto the stack
      // This will have the effect of traversing down the tree
      for (Integer key : this.colOrderedIDoms.getLstKeys()) {
        Integer ndid = this.colOrderedIDoms.getWithKey(key);

        if (ndid.equals(id)) {
          stack.add(key);
        }
      }
    }

    return ret;
  }
}
