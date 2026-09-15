export type OperationType =
  | "READ"
  | "INSERT"
  | "UPDATE"
  | "DELETE"
  | "MERGE"
  | "READ_WRITE"
  | "UNKNOWN";

export type Confidence = "HIGH" | "MEDIUM" | "LOW";

export interface SourceLocation {
  line: number;
  column: number;
  endLine: number;
  endColumn: number;
}

export interface DependencyRelation {
  id: string;
  sourceUnit: string;
  sourceType: string;
  sourceFile: string;
  sourceLocation: SourceLocation;
  targetSchema: string | null;
  targetObject: string;
  targetType: string;
  operation: OperationType;
  confidence: Confidence;
  sqlSnippet: string;
  dynamicSql: boolean;
  warning: string | null;
}

export interface ParseWarning {
  code: string;
  message: string;
  sourceFile: string;
  location: SourceLocation;
}

export interface AnalysisSummary {
  fileCount: number;
  programUnitCount: number;
  relationCount: number;
  unknownRelationCount: number;
  warningCount: number;
  relationCountByOperation: Record<string, number>;
  relationCountByConfidence: Record<string, number>;
}

export interface SourceFileReport {
  fileName: string;
  lineCount: number;
  programUnits: string[];
  relationCount: number;
  warningCount: number;
}

export interface AnalysisResult {
  runId: string;
  parserVersion: string;
  analyzedAt: string;
  summary: AnalysisSummary;
  files: SourceFileReport[];
  relations: DependencyRelation[];
  warnings: ParseWarning[];
}

/** De-duplicated, table level result: one entry per target object. */
export interface ObjectSummary {
  schema: string | null;
  object: string;
  qualifiedName: string;
  operations: OperationType[];
  relationCount: number;
  readCount: number;
  writeCount: number;
  sourceUnits: string[];
  sourceFiles: string[];
  confidence: Confidence;
  dynamicSql: boolean;
  firstLocation: SourceLocation | null;
  sampleSnippet: string;
  note: string;
}

export interface LineageNode {
  id: string;
  nodeType: "TABLE" | "PROGRAM_UNIT";
  name: string;
  unitType: string | null;
  sourceFile: string | null;
  location: SourceLocation | null;
  operation: OperationType;
  confidence: Confidence;
  dynamicSql: boolean;
  sqlSnippet: string | null;
  status: string;
  children: LineageNode[];
}

export interface LineageResult {
  direction: string;
  object: string;
  maxDepth: number;
  reachedDepth: number;
  truncated: boolean;
  roots: LineageNode[];
}
