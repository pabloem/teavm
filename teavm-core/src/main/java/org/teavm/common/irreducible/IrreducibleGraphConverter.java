/*
 *  Copyright 2015 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.common.irreducible;

import com.carrotsearch.hppc.IntOpenHashSet;
import com.carrotsearch.hppc.IntSet;
import com.carrotsearch.hppc.cursors.IntCursor;
import java.util.Arrays;
import org.teavm.common.*;

/**
 * <p>Converts irreducible graph to reducible one using node splitting algorithm described at
 * the paper &ldquo;Handling irreducible loops: optimized node splitting vs. DJ-graphs&rdquo; by
 * Sebastian Unger and Frank Mueller.</p>
 *
 * @author Alexey Andreev
 */
public class IrreducibleGraphConverter {
    private Graph cfg;
    private int totalNodeCount;
    private GraphSplittingBackend backend;

    public void convertToReducible(Graph cfg, int[] weight, GraphSplittingBackend backend) {
        this.backend = backend;
        int[][] identityNodeMap = new int[cfg.size()][];
        for (int i = 0; i < identityNodeMap.length; ++i) {
            identityNodeMap[i] = new int[] { i };
        }
        this.cfg = cfg;
        totalNodeCount = cfg.size();
        handleLoops(new DJGraph(cfg, weight), identityNodeMap);
        this.backend = null;
    }

    private void handleLoops(DJGraph djGraph, int[][] nodeMap) {
        for (int level = djGraph.levelCount() - 1; level >= 0; --level) {
            boolean irreducible = false;
            levelScan:
            for (int node : djGraph.level(level)) {
                for (int pred : djGraph.getGraph().incomingEdges(node)) {
                    if (djGraph.isCrossJoin(pred, node) && djGraph.isSpanningBack(node, pred)) {
                        irreducible = true;
                        break levelScan;
                    }
                }
            }
            if (irreducible) {
                DJGraphNodeFilter filter = new DJGraphNodeFilter(djGraph, level);
                int[][] sccs = GraphUtils.findStronglyConnectedComponents(djGraph.getGraph(),
                        djGraph.level(level), filter);
                for (int[] scc : sccs) {
                    if (scc.length > 1) {
                        handleStronglyConnectedComponent(djGraph, scc, nodeMap);
                        int cls = djGraph.collapse(scc);
                        IntegerArray nodes = new IntegerArray(djGraph.getGraph().size());
                        for (int representative : djGraph.classRepresentatives(cls)) {
                            for (int node : nodeMap[representative]) {
                                nodes.add(node);
                            }
                        }
                        for (int representative : djGraph.classRepresentatives(cls)) {
                            nodeMap[representative] = new int[0];
                        }
                        nodeMap[cls] = nodes.getAll();
                    }
                }
            }
        }
    }

    private void handleStronglyConnectedComponent(DJGraph djGraph, int[] scc, int[][] nodeMap) {
        // Find shared dominator
        int sharedDom = scc[0];
        for (int i = 1; i < scc.length; ++i) {
            sharedDom = djGraph.getDomTree().commonDominatorOf(sharedDom, scc[i]);
        }

        for (int i = 0; i < scc.length; ++i) {
            if (scc[i] == sharedDom) {
                djGraph.collapse(scc);
                return;
            }
        }

        // Partition SCC into domains
        DisjointSet partitions = new DisjointSet();
        int[] sccBack = new int[djGraph.getGraph().size()];
        for (int i = 0; i < scc.length; ++i) {
            partitions.create();
            sccBack[scc[i]] = i;
        }
        for (int i = 0; i < scc.length; ++i) {
            int node = scc[i];
            int idom = djGraph.getDomTree().immediateDominatorOf(node);
            if (idom != sharedDom) {
                partitions.union(i, sccBack[idom]);
            }
        }
        int[] domains = partitions.pack(scc.length);
        int domainCount = 0;
        for (int domain : domains) {
            domainCount = Math.max(domainCount, domain + 1);
        }

        // For each domain calculate its weight
        int[] domainWeight = new int[domainCount];
        for (int i = 0; i < scc.length; ++i) {
            int node = scc[i];
            domainWeight[domains[i]] += djGraph.weightOf(node);
        }

        // Find domain to split around
        int domain = 0;
        int maxWeight = domainWeight[0];
        for (int i = 1; i < domainWeight.length; ++i) {
            if (domainWeight[i] > maxWeight) {
                domain = i;
                maxWeight = domainWeight[i];
            }
        }

        // Find header of this domain
        IntSet domainNodes = new IntOpenHashSet(scc.length);
        for (int i = 0; i < scc.length; ++i) {
            int node = scc[i];
            if (domains[i] == domain) {
                domainNodes.add(node);
            }
        }

        // Split
        splitStronglyConnectedComponent(djGraph, domainNodes, sharedDom, scc, nodeMap);

        // Collapse
        int[] sccAndTop = Arrays.copyOf(scc, scc.length + 1);
        sccAndTop[scc.length] = sharedDom;
        djGraph.collapse(sccAndTop);
    }

    private void splitStronglyConnectedComponent(DJGraph djGraph, IntSet domain, int sharedDom,
            int[] scc, int[][] nodeMap) {
        Arrays.sort(scc);
        // Find SCC \ domain
        int[][] mappedNonDomain = new int[scc.length - domain.size()][];
        int[] domainNodes = new int[domain.size()];
        int[] nonDomainNodes = new int[mappedNonDomain.length];
        int index = 0;
        for (int node : scc) {
            if (!domain.contains(node)) {
                mappedNonDomain[index] = nodeMap[node];
                nonDomainNodes[index] = node;
                ++index;
            }
        }
        int[][] mappedDomain = new int[domain.size()][];
        index = 0;
        for (IntCursor cursor : domain) {
            mappedDomain[index] = nodeMap[cursor.value];
            domainNodes[index] = cursor.value;
            ++index;
        }

        // Delegate splitting to domain
        int[][] newNodes = backend.split(mappedDomain, mappedNonDomain);
        for (int[] nodes : newNodes) {
            totalNodeCount += nodes.length;
        }

        // Calculate mappings
        int[][] newNodeMap = new int[1 + scc.length + newNodes.length][];
        int[] newNodeBackMap = new int[totalNodeCount];
        int[] mappedWeight = new int[newNodeMap.length];
        Arrays.fill(newNodeBackMap, -1);
        newNodeMap[0] = nodeMap[sharedDom];
        newNodeBackMap[sharedDom] = 0;
        mappedWeight[0] = djGraph.weightOf(sharedDom);
        index = 1;
        for (int i = 0; i < mappedDomain.length; ++i) {
            newNodeMap[index] = mappedDomain[i];
            newNodeBackMap[domainNodes[i]] = index;
            mappedWeight[index] = djGraph.weightOf(domainNodes[i]);
            ++index;
        }
        for (int i = 0; i < mappedNonDomain.length; ++i) {
            newNodeMap[index] = mappedNonDomain[i];
            newNodeBackMap[nonDomainNodes[i]] = index;
            mappedWeight[index] = djGraph.weightOf(nonDomainNodes[i]);
            ++index;
        }
        for (int i = 0; i < mappedNonDomain.length; ++i) {
            newNodeMap[index] = newNodes[i];
            mappedWeight[index] = djGraph.weightOf(nonDomainNodes[i]);
            ++index;
        }

        // Build subgraph with new nodes
        GraphBuilder builder = new GraphBuilder(newNodeMap.length);
        for (int succ : cfg.outgoingEdges(sharedDom)) {
            int j = newNodeBackMap[succ];
            if (j >= 0) {
                builder.addEdge(0, j);
            }
        }
        for (int i = 1; i <= mappedDomain.length; ++i) {
            for (int succ : djGraph.getCfg().outgoingEdges(domainNodes[i - 1])) {
                int j = newNodeBackMap[succ];
                if (j > mappedDomain.length) {
                    builder.addEdge(i, j + mappedNonDomain.length);
                } else if (j >= 0) {
                    builder.addEdge(i, j);
                }
            }
        }
        index = 0;
        for (int i = mappedDomain.length + 1; i <= scc.length; ++i) {
            for (int succ : djGraph.getCfg().outgoingEdges(nonDomainNodes[index++])) {
                int j = newNodeBackMap[succ];
                if (j >= 0) {
                    builder.addEdge(i, j);
                    if (j > mappedDomain.length) {
                        builder.addEdge(i + mappedNonDomain.length, j + mappedNonDomain.length);
                    } else {
                        builder.addEdge(i + mappedNonDomain.length, j);
                    }
                }
            }
        }

        handleLoops(new DJGraph(builder.build(), mappedWeight), newNodeMap);
    }

    static class DJGraphNodeFilter implements GraphNodeFilter {
        private DJGraph graph;
        private int level;

        public DJGraphNodeFilter(DJGraph graph, int level) {
            this.graph = graph;
            this.level = level;
        }

        @Override
        public boolean match(int node) {
            return graph.levelOf(node) >= level;
        }
    }
}
