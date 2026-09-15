import { useEffect, useMemo, useState } from "react";
import { analyzeFiles, analyzeText, currentAnalysis, fetchObjectSummaries, traceLineage } from "./api";
import type {
  AnalysisResult,
  DependencyRelation,
  LineageNode,
  LineageResult,
  ObjectSummary,
  OperationType,
} from "./types";

type Tab = "objects" | "relations" | "files" | "warnings" | "lineage";

const OPERATIONS: Array<OperationType | ""> = [
  "",
  "READ",
  "INSERT",
  "UPDATE",
  "DELETE",
  "MERGE",
  "READ_WRITE",
  "UNKNOWN",
];

export default function App() {
  const [analysis, setAnalysis] = useState<AnalysisResult | null>(null);
  const [objects, setObjects] = useState<ObjectSummary[]>([]);
  const [tab, setTab] = useState<Tab>("objects");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [pastedName, setPastedName] = useState("pasted.sql");
  const [pastedSql, setPastedSql] = useState("");
  const [operation, setOperation] = useState<string>("");
  const [confidence, setConfidence] = useState<string>("");
  const [keyword, setKeyword] = useState("");
  const [selected, setSelected] = useState<DependencyRelation | null>(null);
  const [lineageObject, setLineageObject] = useState("");
  const [lineageDirection, setLineageDirection] = useState("UPSTREAM");
  const [lineageDepth, setLineageDepth] = useState(3);
  const [lineage, setLineage] = useState<LineageResult | null>(null);

  useEffect(() => {
    currentAnalysis()
      .then((result) => {
        setAnalysis(result);
        return fetchObjectSummaries().then(setObjects);
      })
      .catch(() => undefined);
  }, []);

  const relations = useMemo(() => {
    if (!analysis) {
      return [];
    }
    const needle = keyword.trim().toUpperCase();
    return analysis.relations.filter((relation) => {
      if (operation && relation.operation !== operation) {
        return false;
      }
      if (confidence && relation.confidence !== confidence) {
        return false;
      }
      if (!needle) {
        return true;
      }
      return (
        relation.sourceUnit.toUpperCase().includes(needle) ||
        relation.targetObject.toUpperCase().includes(needle) ||
        `${relation.targetSchema ?? ""}.${relation.targetObject}`.toUpperCase().includes(needle) ||
        relation.sqlSnippet.toUpperCase().includes(needle)
      );
    });
  }, [analysis, operation, confidence, keyword]);

  const knownObjects = useMemo(() => {
    if (!analysis) {
      return [];
    }
    const keys = new Set<string>();
    analysis.relations.forEach((relation) => {
      keys.add(relation.targetSchema ? `${relation.targetSchema}.${relation.targetObject}` : relation.targetObject);
    });
    return Array.from(keys).sort();
  }, [analysis]);

  async function withBusy(action: () => Promise<AnalysisResult>) {
    setBusy(true);
    setError("");
    try {
      const result = await action();
      setAnalysis(result);
      setObjects(await fetchObjectSummaries().catch(() => []));
      setSelected(null);
      setLineage(null);
      setTab("objects");
    } catch (exception) {
      setError(exception instanceof Error ? exception.message : String(exception));
    } finally {
      setBusy(false);
    }
  }

  async function runLineage() {
    if (!lineageObject.trim()) {
      return;
    }
    setBusy(true);
    setError("");
    try {
      setLineage(await traceLineage(lineageObject.trim(), lineageDirection, lineageDepth));
    } catch (exception) {
      setError(exception instanceof Error ? exception.message : String(exception));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="app">
      <header>
        <div>
          <h1>OSDA 存储过程依赖分析</h1>
          <p className="subtitle">
            离线静态分析 Oracle SQL/PLSQL 的表级读写依赖，只做静态解析，不连接数据库、不执行任何 SQL。
          </p>
        </div>
        <div className="parser">
          {analysis ? `解析器：${analysis.parserVersion}` : "尚未执行分析"}
        </div>
      </header>

      <section className="panel">
        <h2>1. 输入</h2>
        <div className="input-grid">
          <div className="input-block">
            <label className="label">上传 .sql 文件（支持多选）</label>
            <input
              type="file"
              accept=".sql,.pls,.pkb,.pks,.txt"
              multiple
              onChange={(event) => {
                const files = event.target.files;
                if (files && files.length > 0) {
                  void withBusy(() => analyzeFiles(files));
                }
              }}
            />
          </div>
          <div className="input-block">
            <label className="label">粘贴代码</label>
            <input
              className="text-input"
              value={pastedName}
              onChange={(event) => setPastedName(event.target.value)}
              placeholder="文件名，例如 proc_demo.sql"
            />
            <textarea
              value={pastedSql}
              onChange={(event) => setPastedSql(event.target.value)}
              placeholder="CREATE OR REPLACE PROCEDURE ... 或普通 DML"
              rows={6}
            />
            <button
              disabled={busy || pastedSql.trim().length === 0}
              onClick={() => void withBusy(() => analyzeText(pastedName, pastedSql))}
            >
              分析粘贴代码
            </button>
          </div>
        </div>
        {busy && <p className="hint">正在解析…</p>}
        {error && <p className="error">{error}</p>}
      </section>

      {analysis && (
        <>
          <section className="panel">
            <h2>2. 概览</h2>
            <div className="cards">
              <Card label="文件" value={analysis.summary.fileCount} />
              <Card label="程序单元" value={analysis.summary.programUnitCount} />
              <Card label="依赖关系" value={analysis.summary.relationCount} />
              <Card label="不确定关系" value={analysis.summary.unknownRelationCount} tone="warn" />
              <Card label="解析告警" value={analysis.summary.warningCount} tone="warn" />
            </div>
            <div className="badges">
              {Object.entries(analysis.summary.relationCountByOperation).map(([key, value]) => (
                <span key={key} className={`badge op-${key.toLowerCase()}`}>
                  {key} {value}
                </span>
              ))}
              {Object.entries(analysis.summary.relationCountByConfidence).map(([key, value]) => (
                <span key={key} className={`badge conf-${key.toLowerCase()}`}>
                  置信度 {key} {value}
                </span>
              ))}
            </div>
            <div className="actions">
              <a className="link-button" href="/api/export/objects.csv">
                导出对象汇总 CSV
              </a>
              <a className="link-button" href="/api/export/relations.csv">
                导出关系 CSV
              </a>
              <a className="link-button" href="/api/export/analysis.json">
                导出分析结果 JSON
              </a>
              <span className="hint">
                分析时间：{new Date(analysis.analyzedAt).toLocaleString()}，运行号：{analysis.runId}
              </span>
            </div>
          </section>

          <section className="panel">
            <nav className="tabs">
              <button className={tab === "objects" ? "active" : ""} onClick={() => setTab("objects")}>
                表级汇总（去重）{objects.length > 0 ? ` (${objects.length})` : ""}
              </button>
              <button className={tab === "relations" ? "active" : ""} onClick={() => setTab("relations")}>
                依赖关系
              </button>
              <button className={tab === "files" ? "active" : ""} onClick={() => setTab("files")}>
                文件与程序单元
              </button>
              <button className={tab === "warnings" ? "active" : ""} onClick={() => setTab("warnings")}>
                解析告警 ({analysis.warnings.length})
              </button>
              <button className={tab === "lineage" ? "active" : ""} onClick={() => setTab("lineage")}>
                上下游追溯
              </button>
            </nav>

            {tab === "objects" && (
              <div>
                <p className="hint">
                  按目标对象去重后的结果：同一对象的多次出现合并为一行，操作集合、读写次数与涉及的
                  程序单元一并汇总。点击任意一行可跳到该对象的原始依赖明细与证据。
                </p>
                <table className="grid">
                  <thead>
                    <tr>
                      <th>对象</th>
                      <th>操作</th>
                      <th>关系数</th>
                      <th>读 / 写</th>
                      <th>程序单元</th>
                      <th>涉及文件</th>
                      <th>置信度</th>
                      <th>动态</th>
                      <th>说明</th>
                    </tr>
                  </thead>
                  <tbody>
                    {objects.map((item) => (
                      <tr
                        key={item.qualifiedName}
                        onClick={() => {
                          setOperation("");
                          setConfidence("");
                          setKeyword(item.qualifiedName);
                          setTab("relations");
                        }}
                      >
                        <td>{item.qualifiedName}</td>
                        <td>
                          {item.operations.map((operation) => (
                            <span key={operation} className={`badge op-${operation.toLowerCase()}`}>
                              {operation}
                            </span>
                          ))}
                        </td>
                        <td>{item.relationCount}</td>
                        <td>
                          {item.readCount} / {item.writeCount}
                        </td>
                        <td title={item.sourceUnits.join("、")}>
                          {item.sourceUnits.length} 个
                        </td>
                        <td title={item.sourceFiles.join("、")}>{item.sourceFiles.length} 个</td>
                        <td>
                          <span className={`badge conf-${item.confidence.toLowerCase()}`}>
                            {item.confidence}
                          </span>
                        </td>
                        <td>{item.dynamicSql ? "是" : "否"}</td>
                        <td>{item.note}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            {tab === "relations" && (
              <div>
                <div className="filters">
                  <select value={operation} onChange={(event) => setOperation(event.target.value)}>
                    {OPERATIONS.map((item) => (
                      <option key={item || "all"} value={item}>
                        {item === "" ? "全部操作" : item}
                      </option>
                    ))}
                  </select>
                  <select value={confidence} onChange={(event) => setConfidence(event.target.value)}>
                    <option value="">全部置信度</option>
                    <option value="HIGH">HIGH</option>
                    <option value="MEDIUM">MEDIUM</option>
                    <option value="LOW">LOW</option>
                  </select>
                  <input
                    className="text-input"
                    placeholder="按程序单元、目标对象或 SQL 片段搜索"
                    value={keyword}
                    onChange={(event) => setKeyword(event.target.value)}
                  />
                  <span className="hint">{relations.length} 条</span>
                </div>
                <div className="split">
                  <table className="grid">
                    <thead>
                      <tr>
                        <th>来源程序单元</th>
                        <th>操作</th>
                        <th>目标对象</th>
                        <th>置信度</th>
                        <th>位置</th>
                        <th>动态</th>
                      </tr>
                    </thead>
                    <tbody>
                      {relations.map((relation) => (
                        <tr
                          key={relation.id}
                          className={selected?.id === relation.id ? "selected" : ""}
                          onClick={() => setSelected(relation)}
                        >
                          <td title={`${relation.sourceType} ${relation.sourceFile}`}>
                            {relation.sourceUnit}
                          </td>
                          <td>
                            <span className={`badge op-${relation.operation.toLowerCase()}`}>
                              {relation.operation}
                            </span>
                          </td>
                          <td>
                            {relation.targetSchema ? `${relation.targetSchema}.` : ""}
                            {relation.targetObject}
                          </td>
                          <td>
                            <span className={`badge conf-${relation.confidence.toLowerCase()}`}>
                              {relation.confidence}
                            </span>
                          </td>
                          <td>
                            {relation.sourceFile}:{relation.sourceLocation.line}
                          </td>
                          <td>{relation.dynamicSql ? "是" : "否"}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  <aside className="evidence">
                    <h3>证据</h3>
                    {selected ? (
                      <>
                        <dl>
                          <dt>来源程序单元</dt>
                          <dd>
                            {selected.sourceUnit}（{selected.sourceType}）
                          </dd>
                          <dt>目标对象</dt>
                          <dd>
                            {selected.targetSchema ? `${selected.targetSchema}.` : ""}
                            {selected.targetObject}
                          </dd>
                          <dt>操作 / 置信度</dt>
                          <dd>
                            {selected.operation} / {selected.confidence}
                          </dd>
                          <dt>源码位置</dt>
                          <dd>
                            {selected.sourceFile}:{selected.sourceLocation.line}:
                            {selected.sourceLocation.column}
                          </dd>
                          <dt>动态 SQL</dt>
                          <dd>{selected.dynamicSql ? "是（置信度已降级）" : "否"}</dd>
                          {selected.warning && (
                            <>
                              <dt>告警</dt>
                              <dd>{selected.warning}</dd>
                            </>
                          )}
                        </dl>
                        <pre>{selected.sqlSnippet}</pre>
                      </>
                    ) : (
                      <p className="hint">点击左侧任意一行查看原始 SQL 片段与行号证据。</p>
                    )}
                  </aside>
                </div>
              </div>
            )}

            {tab === "files" && (
              <table className="grid">
                <thead>
                  <tr>
                    <th>文件</th>
                    <th>行数</th>
                    <th>关系数</th>
                    <th>告警数</th>
                    <th>程序单元</th>
                  </tr>
                </thead>
                <tbody>
                  {analysis.files.map((file) => (
                    <tr key={file.fileName}>
                      <td>{file.fileName}</td>
                      <td>{file.lineCount}</td>
                      <td>{file.relationCount}</td>
                      <td>{file.warningCount}</td>
                      <td className="units">{file.programUnits.join("、")}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}

            {tab === "warnings" && (
              <table className="grid">
                <thead>
                  <tr>
                    <th>代码</th>
                    <th>文件</th>
                    <th>位置</th>
                    <th>说明</th>
                  </tr>
                </thead>
                <tbody>
                  {analysis.warnings.map((warning, index) => (
                    <tr key={`${warning.code}-${index}`}>
                      <td>{warning.code}</td>
                      <td>{warning.sourceFile}</td>
                      <td>{warning.location.line}</td>
                      <td>{warning.message}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}

            {tab === "lineage" && (
              <div>
                <div className="filters">
                  <input
                    className="text-input"
                    list="known-objects"
                    placeholder="目标对象，例如 ALMP.DM_O_TARGET"
                    value={lineageObject}
                    onChange={(event) => setLineageObject(event.target.value)}
                  />
                  <datalist id="known-objects">
                    {knownObjects.map((object) => (
                      <option key={object} value={object} />
                    ))}
                  </datalist>
                  <select
                    value={lineageDirection}
                    onChange={(event) => setLineageDirection(event.target.value)}
                  >
                    <option value="UPSTREAM">上游（谁写入它、它们又读了什么）</option>
                    <option value="DOWNSTREAM">下游（谁读取它、它们又写了什么）</option>
                  </select>
                  <select
                    value={lineageDepth}
                    onChange={(event) => setLineageDepth(Number(event.target.value))}
                  >
                    {[1, 2, 3, 4, 5].map((depth) => (
                      <option key={depth} value={depth}>
                        深度 {depth}
                      </option>
                    ))}
                  </select>
                  <button disabled={busy} onClick={() => void runLineage()}>
                    查询
                  </button>
                </div>
                {lineage && (
                  <>
                    <p className="hint">
                      {lineage.direction} ｜ 起始对象 {lineage.object} ｜ 实际到达深度{" "}
                      {lineage.reachedDepth} / {lineage.maxDepth}
                      {lineage.truncated ? "（已截断，存在更深关系）" : ""}
                    </p>
                    <div className="tree">
                      {lineage.roots.map((root) => (
                        <LineageBranch key={root.id} node={root} />
                      ))}
                    </div>
                  </>
                )}
              </div>
            )}
          </section>
        </>
      )}
    </div>
  );
}

function Card({ label, value, tone }: { label: string; value: number; tone?: "warn" }) {
  return (
    <div className={`card ${tone === "warn" ? "card-warn" : ""}`}>
      <span className="card-label">{label}</span>
      <span className="card-value">{value}</span>
    </div>
  );
}

function LineageBranch({ node }: { node: LineageNode }) {
  const [open, setOpen] = useState(true);
  const label = node.nodeType === "TABLE" ? node.name : `${node.name}()`;
  return (
    <div className="tree-node">
      <div className="tree-row">
        {node.children.length > 0 ? (
          <button className="tree-toggle" onClick={() => setOpen(!open)}>
            {open ? "−" : "+"}
          </button>
        ) : (
          <span className="tree-toggle placeholder" />
        )}
        <span className={`badge tree-${node.nodeType.toLowerCase()}`}>
          {node.nodeType === "TABLE" ? "表" : "程序单元"}
        </span>
        <span className="tree-name">{label}</span>
        <span className={`badge op-${node.operation.toLowerCase()}`}>{node.operation}</span>
        <span className={`badge conf-${node.confidence.toLowerCase()}`}>{node.confidence}</span>
        {node.status !== "DIRECT" && node.status !== "ROOT" && (
          <span className="badge status">{node.status}</span>
        )}
        {node.dynamicSql && <span className="badge status">动态 SQL</span>}
        {node.sourceFile && (
          <span className="tree-meta">
            {node.sourceFile}:{node.location?.line ?? 0}
          </span>
        )}
      </div>
      {open &&
        node.children.map((child) => <LineageBranch key={child.id} node={child} />)}
    </div>
  );
}
