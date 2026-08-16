# 简历项目表述版本记录

## 文档说明

- 用途：记录该项目在简历中的不同版本表述，便于后续持续迭代
- 规则：每次修改新增一个版本，不覆盖旧版本
- 当前项目：Spring AI 智能 Agent 系统

---

## V1 原始版

### 项目描述

基于 Spring AI 框架构建的智能 AI Agent 系统，实现了自主决策、工具调用和知识库检索功能。系统采用 Think-Execute 循环机制，支持多模型切换、RAG 检索和实时通信，能够完成复杂的多步骤任务。

### 技术栈

Spring Boot 3.5.8、Spring AI 1.1.0、PostgreSQL、pgvector、MyBatis、SSE

### 主要职责

- 设计 Agent 核心引擎：Think-Execute 循环 + 状态机，关闭 Spring AI 自动工具执行，掌握执行控制权
- 实现工具调用系统：固定工具 + 可选工具，通过 MethodToolCallbackProvider 动态注册
- RAG 知识库检索：接入 bge-m3 Embedding 模型 + pgvector 向量数据库，支持语义相似度搜索
- 多模型架构：设计 ChatClientRegistry 注册表模式，支持 DeepSeek、智谱AI 等多模型动态切换
- SSE 实时通信：基于 SseEmitter 实现 Agent 执行状态的实时推送，支持 AI_THINKING、AI_EXECUTING 等多状态展示

### 项目成果

- Agent 支持多步骤循环执行，具备终止机制防止无限循环
- 支持 DeepSeek、智谱AI 等多种大语言模型切换
- 基于 pgvector 实现向量相似度检索，支持多知识库配置

---

## V2 建议版

### 版本说明

- 调整目标：让表述与现有代码实现更一致，减少被面试追问时的风险
- 调整原则：保留亮点，收窄未完全落地或代码证据不足的说法

### 项目描述

基于 Spring AI 构建智能 Agent 系统，围绕 Think-Execute 循环实现多步任务执行，支持工具调用、知识库检索、多模型切换与 SSE 实时推送。

### 技术栈

Spring Boot 3.5.8、Spring AI 1.1.0、PostgreSQL、pgvector、MyBatis、SSE

### 主要职责

- 设计 Agent 执行核心，基于 Think-Execute 循环实现多步骤任务处理，并关闭 Spring AI 默认工具自动执行，改为手动控制调用与终止流程
- 实现工具调用机制，区分固定工具与可选工具，基于 MethodToolCallbackProvider 按 Agent 配置动态组装运行时工具集
- 实现 RAG 检索能力，接入 Embedding 服务并结合 PostgreSQL + pgvector 完成向量相似度搜索
- 设计多模型接入方案，基于 ChatClientRegistry 注册表支持 DeepSeek、智谱 AI 等模型按配置切换
- 基于 SseEmitter 实现 Agent 执行结果实时推送，支持消息增量返回与会话联动展示

### 项目成果

- 支持多步循环执行与最大步数终止控制，避免无限调用
- 支持 DeepSeek、智谱 AI 等模型切换
- 支持多知识库配置下的向量检索与语义召回

---

## V3 精简版

### 版本说明

- 调整目标：压缩简历篇幅，保留核心技术亮点
- 适用场景：项目经历区域空间有限，需要更高信息密度

### 项目描述

基于 Spring AI 构建智能 Agent 系统，采用 Think-Execute 循环实现多步任务执行，支持工具调用、RAG 检索、多模型切换与 SSE 实时推送。

### 技术栈

Spring Boot 3.5.8、Spring AI 1.1.0、PostgreSQL、pgvector、MyBatis、SSE

### 主要职责

- 设计 Agent 执行流程，关闭 Spring AI 默认工具自动执行，手动控制工具调用与终止机制
- 实现固定工具与可选工具机制，支持按配置动态组装运行时工具集
- 基于 PostgreSQL + pgvector 实现 RAG 向量检索，支持多知识库配置
- 设计 ChatClientRegistry 多模型切换方案，支持 DeepSeek、智谱 AI 接入

### 项目成果

- 支持多步循环执行与终止控制
- 支持多模型切换与知识库语义检索

---

## V4 职责成果合并版

### 版本说明

- 调整目标：将项目成果直接融入职责描述，进一步压缩篇幅
- 适用场景：简历空间紧张，希望每条职责同时体现实现内容和结果

### 项目描述

基于 Spring AI 构建智能 Agent 系统，采用 Think-Execute 循环实现多步任务执行，支持工具调用、RAG 检索、多模型切换与 SSE 实时推送。

### 技术栈

Spring Boot 3.5.8、Spring AI 1.1.0、PostgreSQL、pgvector、MyBatis、SSE

### 主要职责

- 设计 Agent 执行核心，基于 Think-Execute 循环实现多步任务处理，关闭 Spring AI 默认工具自动执行，并通过终止控制避免无限循环
- 实现固定工具与可选工具机制，基于 MethodToolCallbackProvider 按 Agent 配置动态组装运行时工具集
- 实现 RAG 检索能力，接入 Embedding 服务并结合 PostgreSQL + pgvector 完成向量相似度搜索，支持多知识库语义检索
- 设计 ChatClientRegistry 多模型接入方案，支持 DeepSeek、智谱 AI 等模型按配置切换
- 基于 SseEmitter 实现 Agent 执行结果实时推送，支持消息增量返回与会话联动展示

---

## V5 完整版

### 版本说明

- 修改原因：原版本缺少 RAG 评测驱动优化细节、用户记忆系统；SSE 多状态类型仅声明未实际发送，旧表述不准确
- 主要调整：
  - RAG 条目展开为评测驱动的完整优化链路，补充具体指标与多知识库联合检索
  - 新增用户级长期记忆系统条目
  - 工具系统补充会话级有状态工具隔离
  - 修正 SSE 表述：删除未实际使用的 AI_THINKING/AI_EXECUTING 枚举声明，改为准确描述

### 项目描述

基于 Spring AI 构建智能 Agent 系统，采用 Think-Execute 循环实现多步任务执行，支持工具调用、RAG 检索与评测优化、用户长期记忆管理、多模型切换与 SSE 实时推送。

### 技术栈

Spring Boot 3.5.8、Spring AI 1.1.0、PostgreSQL、pgvector、MyBatis、SSE、Ollama (bge-m3)

### 主要职责

- 设计 Agent 执行核心，基于 Think-Execute 循环 + 状态机实现多步任务处理，关闭 Spring AI 默认工具自动执行，通过最大步数限制与 TerminateTool 双重终止控制避免无限循环
- 实现固定工具与可选工具机制，基于 MethodToolCallbackProvider 按 Agent 配置动态组装运行时工具集，通过 bindRuntimeToolContext 实现有状态工具的会话级实例隔离
- 实现 RAG 检索链路并完成多轮评测驱动优化：接入 bge-m3 Embedding 模型 + pgvector 向量检索，支持单次调用联合检索多个知识库；自研轻量 rerank 排序层（标题/正文/路径多维打分），引入标题字段 BM25 全文检索作为混合召回补充；设计 Query Rewrite 与会话 retrievalContext 持久化机制实现多轮追问上下文复用；自建离线评测框架覆盖 Recall@1/3/5/10、MRR、命中分布等指标，正文改写召回率从 0.29 提升至 0.99，多轮追问由完全 miss 提升至首位命中
- 实现用户级长期记忆系统：已确认记忆与候选记忆双表架构，候选→确认的审核链路，支持按约束/学习目标/偏好/背景四类自动分类提取，Agent 运行时自动注入已确认记忆到系统上下文
- 设计 ChatClientRegistry 多模型接入方案，支持 DeepSeek、智谱 AI 等模型按配置切换
- 基于 SseEmitter 实现 Agent 执行结果实时推送，支持消息增量返回与会话联动展示

---

## 后续版本记录模板

### VX 版本名

#### 版本说明

- 修改原因：
- 主要调整：

#### 项目描述

待补充

#### 技术栈

待补充

#### 主要职责

- 待补充

#### 项目成果

- 待补充
