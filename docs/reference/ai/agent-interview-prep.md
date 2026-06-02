# Agent八股项目联动整理

# 说明

- 这份文档只基于当前 `JChatMind` 仓库实现整理。
- 目标不是写通用八股大全，而是沉淀“面试题怎么往项目上落”。
- 后面你每发一道题，都可以继续补到这份文档里。

# 基础概念

### 1. 什么是 Agent，与大模型有什么本质不同？

#### 先给结论

大模型是“认知和生成能力”的核心，擅长理解、推理和生成文本；Agent 则是在大模型外面包了一层任务执行系统，让模型不只是回答问题，还能围绕目标持续做决策、调用工具、读取外部信息，并根据执行结果继续推进任务。

一句话讲：

- 大模型更像大脑
- Agent 更像大脑外面接了一套行动系统

#### 1. 你这题最容易讲偏的地方

不要把 Agent 和大模型讲成并列替代关系。

更准确的关系是：

- 大模型是能力底座
- Agent 是基于大模型搭出来的上层运行机制

所以不是“有了 Agent 就不是大模型了”，而是“Agent 通常要借助大模型来做决策”。

#### 2. 本质差异是什么

普通大模型调用，很多时候是：

- 你给一个输入
- 模型生成一个输出
- 这次调用基本就结束

而 Agent 通常是：

- 先理解目标
- 判断当前信息够不够
- 不够就决定是否调用工具或检索外部信息
- 拿到结果后继续思考下一步
- 直到任务完成或达到终止条件

所以本质差异不在“会不会说话”，而在：

- 是否有多步闭环
- 是否有自主决策
- 是否能和外部环境交互
- 是否围绕任务持续推进

#### 3. 一个更标准的理解方式

你可以把 Agent 理解成：

`LLM + Memory + Tool Use + Planning/Decision + Loop`

其中：

- LLM 负责理解和推理
- Memory 负责上下文
- Tool Use 负责外部能力
- Decision 负责下一步判断
- Loop 负责多轮推进直到结束

#### 4. 结合你项目怎么讲

在 `JChatMind` 里，这个区别非常清楚：

- `ChatClient` 代表大模型能力本身
- `JChatMind` 代表 Agent 运行机制

大模型只负责在 `think()` 阶段做决策和生成 `toolCalls` 或自然语言输出；
真正让它成为 Agent 的，是外层这套：

- `run()`
- `step()`
- `think()`
- `execute()`
- `MAX_STEPS`
- 工具执行
- RAG 检索
- 消息持久化
- SSE 推送

所以你项目不是“简单接了个大模型聊天接口”，而是“在大模型外面做了一层可执行的 Agent Loop”。

#### 5. 面试里最稳的说法

你可以这样讲：

大模型本质上是一个通用的语言理解和生成引擎，主要负责推理和生成；Agent 则是在大模型外面增加了目标驱动、多步决策、工具调用、记忆和执行闭环，让系统不只是回答问题，而是能围绕任务持续推进。换句话说，大模型更像认知核心，Agent 更像把认知核心接到外部行动系统上的完整执行体。

#### 6. 一句压缩版

大模型负责“想”，Agent 负责“想完之后决定怎么做，并把事情推进下去”。

#### 7. 可以把 Agent 记成哪几个核心点

如果为了面试速记，你可以先记成三点：

- 工具调用
- 记忆机制
- 多步推理与自我修正

这个总结方向是对的，但它还少一个更根的东西：

- 目标驱动下的决策闭环

更稳的讲法是：

Agent 常见的四个核心可以概括为：

- 有目标，不是单轮问答
- 有决策闭环，能判断下一步做什么
- 有外部能力，能调用工具和访问环境
- 有上下文延续，能基于记忆和执行结果继续推进

其中：

- 工具调用是行动能力
- 记忆机制是上下文能力
- 多步推理与自我修正是推进复杂任务的过程能力

所以你说的三点可以当速记，但面试里更建议把“目标驱动 + 决策闭环”补出来，这样更完整。

### 2. Agent 的核心框架由哪些组件组成？

#### 先给结论

你说的 `LLM + 工具 + 记忆` 是对的，但还不够完整。

更稳的讲法是，Agent 框架通常至少有四个核心组件：

- LLM
- Planning / Controller / Loop
- Tools
- Memory

如果再展开一点，还可以补一个：

- Observation / Feedback

#### 1. LLM

LLM 是认知核心，负责：

- 理解用户目标
- 基于上下文做推理
- 决定下一步动作
- 生成自然语言或结构化调用意图

它更像大脑，不直接执行外部动作。

#### 2. Planning / Controller / Loop

这是很多人最容易漏掉的部分。

Agent 不是单次问答，而是一个持续推进任务的系统，所以一定要有：

- 当前状态判断
- 下一步动作选择
- 工具调用后的继续推进
- 终止条件控制

这一层有时叫：

- Planner
- Controller
- Executor
- Agent Loop

名字不同，但本质都是“任务闭环控制层”。

#### 补充：规划模块和控制执行闭环是什么关系

可以把规划模块理解成控制闭环里的一个子能力，但两者不能完全画等号。

更准确地说：

- Planning 更偏“下一步怎么做”
- Controller / Loop 更偏“把决策、执行、反馈和终止条件组织成完整流程”

也就是说：

`Planning ⊂ Controller / Agent Loop`

控制执行闭环通常至少包括：

- 当前状态判断
- 下一步动作决策
- 是否调用工具
- 执行工具
- 接收结果反馈
- 判断是否继续
- 判断是否结束

所以规划只是其中的一环，不是全部。

结合 `JChatMind` 来看：

- `think()` 可以理解为局部规划 / 决策
- `execute()` 负责真正执行
- `run()` 负责循环推进和终止控制

因此你项目里不是单独做了一个 Planner，而是把规划能力嵌进了完整的 Agent Loop 里。

#### 3. Tools

工具负责给 Agent 外部行动能力，比如：

- 搜索
- 调接口
- 查数据库
- 文件系统操作
- 发邮件

没有工具时，模型很多时候只能“会说不会做”。

#### 4. Memory

记忆负责上下文延续。

通常可以分成：

- 短期记忆
  - 当前会话上下文
- 长期记忆
  - 跨会话信息、用户偏好、历史知识沉淀

没有记忆，Agent 很难处理连续任务，也很难做长链路协作。

#### 5. Observation / Feedback

这一层有时不会单独拎出来，但实际上很重要。

Agent 调完工具后，需要拿到外部世界的反馈，比如：

- 工具执行结果
- 检索结果
- 错误信息
- 环境变化

然后再把这些结果送回 LLM 继续决策。

所以 Agent 不是“想一次就结束”，而是“观察 -> 决策 -> 执行 -> 再观察”的闭环。

#### 6. 你这版回答哪里对，哪里不够

对的地方：

- 你抓住了 LLM、工具、记忆这三个主体能力
- 你也知道工具是为了让 Agent 和外部世界交互
- 你也知道记忆分短期和长期

不够的地方：

- 少了控制层 / Loop
- “记忆使 Agent 不会失忆”这个说法可以再工程化一点
- 最好把“工具、记忆、推理”放到“目标驱动闭环”里讲

#### 7. 面试里最稳的说法

你可以这样讲：

Agent 框架通常由几个核心组件组成。第一是 LLM，负责理解目标、推理和生成决策；第二是控制与规划层，也就是 Agent Loop，负责判断下一步做什么、是否调用工具、以及什么时候结束；第三是工具层，负责提供搜索、接口调用、数据库访问等外部执行能力；第四是记忆层，负责维护短期上下文和长期信息沉淀。很多实现里还会强调观察反馈层，也就是工具结果和环境变化回流给模型继续决策。整体上，Agent 其实就是把 LLM、工具、记忆和执行闭环组织成一个面向任务推进的系统。

#### 8. 结合你项目怎么讲

在 `JChatMind` 里，这几个组件对应得很清楚：

- LLM：`ChatClient`
- 控制层：`run() -> step() -> think() -> execute()`
- 工具层：`ToolCallback`、`ToolCallingManager`
- 记忆层：`MessageWindowChatMemory` + 数据库存档恢复
- 反馈层：工具执行结果回填 `chatMemory`，再进入下一轮 `think()`

所以你项目里最加分的点，不只是有 LLM、工具和记忆，而是把它们用一套 `Think -> Execute` 闭环真正串起来了。

### 3. 我的工具是怎么编写的？

#### 先给结论

你项目里的工具不是随便写一个方法就结束，而是按一套统一规范来做的：

- 先实现统一 `Tool` 接口
- 再注册成 Spring Bean
- 用 `@org.springframework.ai.tool.annotation.Tool` 暴露真正给模型调用的方法
- 最后在运行时通过 `MethodToolCallbackProvider` 转成 `ToolCallback`，交给模型使用

所以它本质上是“业务实现 + 工具描述 + 运行时注册”三层组合。

#### 1. 统一工具接口

所有工具类先实现你自己的 `Tool` 接口：

- `getName()`
- `getDescription()`
- `getType()`

对应文件：

- `backend_v2/src/main/java/com/kama/jchatmind/agent/tools/Tool.java`
- `backend_v2/src/main/java/com/kama/jchatmind/agent/tools/ToolType.java`

这层的作用是让工具先在业务侧有统一抽象，方便后面分类和组装。

#### 2. 工具分固定和可选两类

你项目里有：

- `FIXED`
- `OPTIONAL`

对应实现：

- `ToolType`
- `ToolFacadeServiceImpl`

比如：

- `KnowledgeTools` 是固定工具
- `TerminateTool` 是固定工具
- `EmailTools` 是可选工具
- `DataBaseTools` 是可选工具

这样运行时可以按 Agent 配置动态决定哪些工具真正暴露给模型。

#### 3. 真正给模型调用的是带 `@Tool` 注解的方法

每个工具类里，真正暴露给模型的，不是类本身，而是具体方法，比如：

- `KnowledgeTools.knowledgeQuery(...)`
- `EmailTools.sendEmail(...)`
- `DataBaseTools.query(...)`
- `TerminateTool.terminate()`

这些方法上会加：

```java
@org.springframework.ai.tool.annotation.Tool(
    name = "...",
    description = "..."
)
```

这里的 `description` 很关键，因为模型就是根据这个描述判断：

- 什么时候该用这个工具
- 用哪个工具
- 参数大概应该怎么组织

所以工具描述其实是工具设计里非常重要的一部分。

#### 4. 工具描述怎么写

你项目里的写法已经体现出一个比较正确的思路：

- 说明工具用途
- 说明适用场景
- 说明参数含义
- 说明边界限制

比如数据库工具写了：

- 只允许 `SELECT`
- 仅用于只读查询

邮件工具写了：

- `to`、`subject`、`content` 都是必填
- 实际发送是异步执行

知识库工具写了：

- 输入知识库 ID 和 query
- 返回相关片段

这类描述越清晰，模型越不容易乱调工具。

#### 5. 工具内部还做了业务保护

你的工具不是只暴露一个接口，还在内部补了约束逻辑，比如：

- `DataBaseTools` 限制只能执行 `SELECT`
- `EmailTools` 做了参数非空校验和邮箱格式校验
- `FileSystemTools` 做了路径遍历防护

这说明你项目里的工具设计不是“完全信任模型”，而是让模型只负责决策，真正执行时仍然由后端兜底校验。

#### 6. 运行时怎么注册给模型

这一步是在 `JChatMindFactory` 里完成的。

流程是：

1. 先通过 `ToolFacadeService` 收集 Spring 容器中的工具 Bean
2. 按 `FIXED` / `OPTIONAL` 分类
3. 根据 Agent 配置组装运行时工具集合
4. 用 `MethodToolCallbackProvider` 把工具对象转成 `ToolCallback`
5. 在 `think()` 阶段通过 `.toolCallbacks(...)` 暴露给模型

所以你项目里“工具怎么写”和“工具怎么给模型用”是分开的：

- 写工具：业务层关注能力实现
- 注册工具：运行时关注暴露和组装

#### 7. 结合项目可以怎么讲

你可以这样说：

我项目里的工具先实现了统一 `Tool` 接口，定义名称、描述和工具类型；具体暴露给模型调用的方法，再通过 Spring AI 的 `@Tool` 注解声明名称和 description。工具类注册成 Spring Bean 后，会在运行时由 `ToolFacadeService` 收集，再由 `JChatMindFactory` 根据 Agent 配置动态组装，最后通过 `MethodToolCallbackProvider` 转成 `ToolCallback` 暴露给模型。所以我这边的工具设计，不是写死在 Agent 里的，而是“统一抽象 + 动态注册 + 描述驱动调用”。

#### 8. 一句压缩版

我项目里的工具是按统一接口实现业务能力，再用 `@Tool` 描述方法语义，最后在运行时动态注册成模型可调用的 `ToolCallback`。

### 4. 我的工具 description 是怎么设计的？

#### 先给结论

你项目里的工具 description 不是随便写一句“这个工具能干什么”，而是按“用途 + 适用场景 + 参数说明 + 边界限制”的思路去写，让模型能更准确判断：

- 什么时候该用
- 用哪个工具
- 参数怎么传
- 哪些事情不能做

所以 description 在 Agent 工程里，本质上是“给模型看的调用说明书”。

#### 1. 设计目标

工具 description 的核心目标不是给人看得漂亮，而是让模型做出更稳定的调用决策。

它至少要解决四件事：

- 明确工具职责
- 缩小适用范围
- 说明参数语义
- 说明使用边界

如果 description 太短、太泛，模型就容易：

- 不该调的时候乱调
- 该调的时候不调
- 选错工具
- 参数构造错误

#### 2. 你项目里的具体设计方式

从现有代码看，你基本采用的是这套写法：

##### 第一类：先说用途

比如 `KnowledgeTool`：

- “从指定知识库中执行相似性检索（RAG）”

比如 `sendEmail`：

- “发送邮件到指定的收件人”

这一步先告诉模型，这个工具到底是干什么的。

##### 第二类：再说输入参数

你项目里的 description 会显式写参数，比如：

- `kbsId` 和 `query`
- `to`、`subject`、`content`
- `sql`

这样模型不仅知道“能做什么”，也知道“调用时需要组织哪些字段”。

##### 第三类：补适用场景

比如 `directAnswer` 的思路就是：

- 当用户请求不需要执行操作时调用

这类描述其实是在帮模型做工具选择，而不是只做文档说明。

##### 第四类：补边界和限制

这是你项目里做得比较好的地方。

比如：

- `databaseQuery` 明确写了“仅支持只读 SELECT”
- `sendEmail` 写了实际发送是异步
- `KnowledgeTool` 明确输入的是知识库 ID 和查询文本

这些限制能显著降低模型误用工具的概率。

#### 3. 你这套 description 的设计原则，可以总结成四条

第一，职责单一

不要让一个工具描述看起来什么都能干。职责越聚焦，模型越容易判断。

第二，参数清晰

参数名和参数说明要让模型能直接映射到用户意图。

第三，边界明确

要写清楚只读、异步、限定数据源、限定场景这类约束。

第四，面向模型决策写

description 不是传统 API 文档，它更像 prompt 的一部分，要服务模型判断，而不只是服务开发者阅读。

#### 4. 结合你项目可以怎么讲

你可以这样说：

我项目里的工具 description 主要按“用途、参数、适用场景、边界限制”四个维度设计。因为模型不是靠代码逻辑猜工具，而是靠 description 判断什么时候该调用哪个工具，所以我会尽量把工具职责写窄、把参数写清楚、把限制条件写明白。比如数据库工具我会明确它只支持只读 SELECT，邮件工具会写清楚收件人、主题、正文这几个参数，以及发送是异步的，知识库工具会写清楚它是根据知识库 ID 和 query 做 RAG 检索。这样能降低模型乱调工具和参数构造错误的概率。

#### 5. 一句压缩版

我项目里的工具 description 本质上是给模型看的调用说明书，设计重点是职责清晰、参数明确、边界收紧，让模型更稳定地做工具选择和参数构造。

### 5. 项目的记忆是怎么实现的？上下文怎么记忆？长期记忆怎么实现？

#### 先给结论

你项目里的记忆实现，当前更准确地说是：

- 有短期会话记忆
- 有会话历史持久化与恢复
- 有知识库层面的外部长期知识
- 但还没有做成完整的“用户长期记忆系统”

所以面试里不要把“聊天历史存档”和“真正长期记忆”完全画等号。

#### 1. 短期记忆怎么实现

你当前运行时的短期记忆，核心是 `MessageWindowChatMemory`。

对应实现：

- `backend_v2/src/main/java/com/kama/jchatmind/agent/JChatMind.java`

初始化时会这样做：

- 创建 `MessageWindowChatMemory`
- 根据 Agent 配置设置 `maxMessages`
- 把历史消息加载进当前会话窗口
- 再把系统提示词加进去

也就是说，模型在每轮 `think()` 时看到的不是全量无限历史，而是窗口内的最近一段上下文。

这类设计的作用是：

- 控制 prompt 长度
- 控制 token 成本
- 控制上下文污染
- 控制运行时内存开销

#### 2. 上下文是怎么记忆的

上下文不是靠内存里一直堆着，而是“数据库持久化 + 运行时恢复”。

流程是：

1. 用户消息先落库到 `chat_message`
2. Agent 生成的 `AssistantMessage` 和 `ToolResponseMessage` 也会落库
3. 新一轮运行时，`JChatMindFactory.loadMemory()` 会从数据库读取最近 N 条消息
4. 再把这些消息恢复成 Spring AI 的 `Message` 对象放回 `chatMemory`

这里恢复的消息包括：

- `SystemMessage`
- `UserMessage`
- `AssistantMessage`
- `ToolResponseMessage`

所以你项目里的上下文记忆，不只是普通文本历史，还包括：

- 模型之前的回答
- 工具调用请求
- 工具执行结果

这点很重要，因为 Agent 要继续推理，不能只记得“用户说了什么”，还得记得“前面调过什么工具，工具返回了什么”。

#### 3. 为什么说这是“短期记忆 + 存档恢复”

因为你当前做的是：

- 会话消息持久化存档
- 运行时按窗口恢复最近上下文

这更接近：

- 短期工作记忆
- 会话历史恢复

而不是完整意义上的长期记忆系统。

#### 4. 长期记忆现在有没有

如果严格从 Agent Memory 的定义讲，你项目里目前还没有真正完整落地“长期记忆”。

原因是：

- 没有专门的用户画像记忆
- 没有跨会话偏好提炼
- 没有把历史经验压缩成可检索的 memory store
- 没有自动总结、沉淀、再检索的长期记忆链路

所以这部分面试里不要说太满。

#### 5. 那项目里有什么接近长期记忆的东西

目前最接近长期记忆的有两类：

第一类是聊天历史持久化

- 聊天消息会长期存到数据库
- 但默认只是“存档”，不是“主动提炼后的长期记忆”

第二类是知识库 / RAG

- 文档上传后会切分、embedding、入库
- 检索时通过 `KnowledgeTool -> RagService -> pgvector` 召回知识片段

这更像“外部长时知识存储”，而不是“Agent 自身记忆”。

所以更准确地说：

- 聊天记录是会话历史存档
- 知识库是外部知识记忆
- 真正用户级长期记忆体系还没完整做出来

#### 6. 结合你项目怎么讲最稳

你可以这样说：

我项目里的记忆现在主要分两层。第一层是短期会话记忆，运行时用 `MessageWindowChatMemory` 保存当前会话窗口内的上下文，避免每次都把全量历史塞给模型。第二层是会话历史持久化，用户消息、模型消息和工具结果都会落到数据库里，下一次运行时再由 `JChatMindFactory.loadMemory()` 恢复成 Spring AI 的消息对象，重新装载到上下文里。至于长期记忆，如果严格按 Agent Memory 来说，我现在还没有做完整的用户画像和跨会话偏好沉淀；目前更接近长期信息的是知识库 RAG，它承担的是外部长时知识，而不是用户级长期记忆。

#### 7. 一句压缩版

我项目现在是“短期上下文记忆 + 会话历史恢复 + 知识库外部长时知识”，但严格意义上的用户长期记忆体系还没完全做出来。

### 6. Workflow、Agent、Tools 的概念和区别是什么？

#### 先给结论

这三个概念可以按“粒度和自主性”来理解：

- `Tool` 是单点能力
- `Workflow` 是预定义流程
- `Agent` 是带决策闭环的执行体

一句话讲：

- Tool 解决“能做什么”
- Workflow 解决“按什么固定步骤做”
- Agent 解决“当前该怎么做、下一步怎么推进”

#### 1. Tool 是什么

Tool 指的是暴露给模型或 Agent 的外部能力，一般是一个原子动作，比如：

- 查天气
- 查数据库
- 搜索知识库
- 发邮件
- 读写文件

Tool 的特点是：

- 职责单一
- 输入输出相对明确
- 一次调用做一件事
- 本身通常不负责复杂任务编排

所以 Tool 更像“执行器”或“能力接口”。

#### 2. Workflow 是什么

Workflow 是预先设计好的固定流程，本质上是编排逻辑。

它会把多个步骤按既定顺序串起来，比如：

- 第一步读取输入
- 第二步查数据库
- 第三步生成报告
- 第四步发送邮件

Workflow 的特点是：

- 路径相对固定
- 可预测性强
- 稳定性高
- 更适合重复性任务

所以 Workflow 更像“流程模板”或“有向流程图”。

需要注意：

- Workflow 不等于 Skill
- Skill 只是某些平台对一类复用能力包的封装方式

也就是说，Skill 可以内部实现成一个 Workflow，但 Workflow 不是通用意义上的 Skill 同义词。

#### 3. Agent 是什么

Agent 是在 LLM 外面包了一层目标驱动的决策与执行系统。

它的特点是：

- 有目标
- 能判断当前信息是否足够
- 能决定要不要调 Tool
- 能根据结果继续推进下一步
- 有终止条件和反馈闭环

所以 Agent 不是固定跑一套既定步骤，而是会动态决定路径。

#### 4. 三者最核心的区别

最本质的区别有两点：

第一，自主性不同

- Tool 几乎没有自主性
- Workflow 自主性很弱，主要按预设流程执行
- Agent 自主性最高，会根据上下文动态决策

第二，流程确定性不同

- Tool 是单动作
- Workflow 是固定流程
- Agent 是动态流程

所以你可以把它们看成一个递进关系：

`Tool -> Workflow -> Agent`

#### 补充：为什么说 Workflow 是“流程骨架”

这个理解很重要。

Workflow 的本质不是某个具体能力，而是开发者预先写好的执行骨架。

也就是说，在 Workflow 里：

- 先做什么
- 后做什么
- 哪个条件走哪个分支
- 哪一步调用 LLM
- 哪一步调用 Tool
- 哪一步甚至调用一个 Agent

这些都是由开发者提前编排好的。

所以在 Workflow 视角下：

- LLM 可以是一个节点
- Tool 可以是一个节点
- Agent 也可以是一个节点

但真正决定全局流程怎么走的，不是这些节点自己，而是外层 Workflow。

这就是它和 Agent 的核心差异之一：

- Workflow 的控制权主要在开发者代码里
- Agent 的控制权更多交给模型驱动的动态决策

#### 补充：三者可以怎么配合

一个更完整的工程视角是：

- Tool 提供原子能力
- Workflow 负责固定编排
- Agent 负责动态决策

三者不是互斥关系，常见组合是：

1. Workflow 里调用 Tool
2. Workflow 里嵌一个 Agent 节点
3. Agent 在执行过程中调用多个 Tool
4. Agent 触发某个固定 Workflow

所以关键不是“它们谁替代谁”，而是：

- 固定、稳定、可预测的部分交给 Workflow
- 开放、复杂、不确定的部分交给 Agent
- 具体执行动作交给 Tool

#### 5. 你这版回答哪里对，哪里要修

对的地方：

- 你知道 Tool 是外部能力
- 你知道 Agent 有自主决策
- 你知道 Workflow 适合流程化任务

要修的地方：

第一，`Workflow 是在提示词高度匹配时调用` 这个说法不够准。

更准确地说，Workflow 是预定义的编排逻辑，至于什么时候触发，可以是：

- 用户显式选择
- 路由器判断
- Agent 决策触发
- 系统规则触发

不只是“提示词高度匹配”这一种方式。

第二，`Workflow 通常封装 Skill，skill 可主动调用` 这个说法太平台相关。

更准确是：

- Skill 是某些平台里的能力封装形式
- Workflow 是更通用的流程编排概念

不要把它们直接画等号。

第三，最好补一句：Agent 也不一定非要调用 Workflow。

它可以：

- 直接调用 Tool
- 调用 Workflow
- 甚至自己通过多轮推理临时拼出执行路径

#### 6. 结合你项目怎么讲

你当前的 `JChatMind` 项目里，明确落地的是：

- Agent
- Tools

还没有单独抽象出一个正式的 Workflow 层。

也就是说：

- `KnowledgeTool`、`EmailTools`、`DataBaseTools` 这些是 Tool
- `JChatMind` 的 `Think -> Execute` 是 Agent 执行闭环
- 但项目里没有独立的“固定流程编排引擎”去定义 Workflow

如果后续要做 Workflow，可以在 Agent 外面再加一层固定编排，比如：

- 固定先检索知识库
- 再调用数据库
- 再整理结果
- 最后生成回答

#### 7. 面试里最稳的说法

你可以这样讲：

Tool、Workflow 和 Agent 可以理解成三个不同层次的概念。Tool 是单点能力，比如查天气、查数据库、发邮件；Workflow 是预定义好的固定流程，强调按既定步骤完成重复任务；Agent 则是在大模型外面增加了一层目标驱动的决策与执行闭环，能够根据上下文动态判断下一步做什么。简单说，Tool 是能力接口，Workflow 是固定编排，Agent 是动态决策系统。我的项目里目前明确落地的是 Agent 和 Tools，还没有单独抽象出一层正式的 Workflow 引擎。

#### 8. 一句压缩版

Tool 是单能力，Workflow 是固定流程，Agent 是能动态决策并调用能力推进任务的执行体。

#### 补充：为什么很多生产场景更偏 Agentic Workflow

这个补充非常重要，因为它回答了“三者在真实工程里怎么组合”。

更准确地说，在很多生产场景里，更常见的不是：

- 完全放任 Agent 自主决策

也不是：

- 完全靠 Workflow 把所有路径写死

而是折中成一种组合方式：

- 外层用 Workflow 固定主流程骨架
- 在需要灵活判断的关键节点嵌入 Agent
- 具体执行动作再交给 Tool

这就是很多人说的 `Agentic Workflow`。

##### 为什么不完全靠 Agent

完全依赖 Agent 动态决策的问题通常是：

- 行为不稳定
- 调试难
- 成本容易失控
- 调太多轮时排查问题困难

所以在生产环境里，如果把所有流程控制权都交给 Agent，风险通常比较大。

##### 为什么不完全靠 Workflow

完全写死 Workflow 的问题则是：

- 对复杂和开放输入适应性差
- 很难覆盖所有异常分支
- 一旦场景变化，需要频繁改代码

所以 Workflow 很稳，但灵活性不够。

##### 为什么 Agentic Workflow 更常见

因为它把两者优点拼起来了：

- Workflow 负责确定主流程，保证可控性和可调试性
- Agent 负责复杂判断，补足灵活性
- Tool 负责执行具体动作

也就是说：

- 稳定部分交给 Workflow
- 不确定部分交给 Agent
- 原子能力交给 Tool

##### 面试里怎么讲更稳

不要把“Agentic Workflow 是生产主流”说得太绝对。

更稳的说法是：

在很多生产场景里，常见做法是 Agentic Workflow，也就是用 Workflow 固定主流程骨架，在关键决策点嵌入 Agent，再由 Tool 提供具体执行能力。这样既能保持系统整体可控、可调试，又能在局部场景保留模型的灵活判断能力。

##### 结合你项目怎么讲

你当前的 `JChatMind` 更偏：

- 单 Agent
- 动态 Tool Calling

还没有单独抽出一个正式的 Workflow 编排层。

如果后续往生产化演进，一个自然方向就是：

- 外层先用 Workflow 固定几个主步骤
- 某些开放式判断节点再交给 `JChatMind` 这样的 Agent
- 具体数据库、RAG、邮件等动作继续由 Tool 执行

这样就能从“单 Agent 系统”逐步演进到“Agentic Workflow”。

# 项目一句话版本

这是一个基于 Spring AI 的单 Agent 学习项目，核心不是简单调大模型接口，而是自己接管了 Agent 的 `Think -> Execute` 循环，并把工具调用、RAG、多模型切换、消息持久化和 SSE 实时推送串成了一条执行链路。

# 项目核心链路

### 1. 入口不是同步阻塞跑 Agent

用户消息先入库，再发布聊天事件，由异步监听器创建运行时 Agent 并执行。

对应实现：

- `backend_v2/src/main/java/com/kama/jchatmind/service/impl/ChatMessageFacadeServiceImpl.java`
- `backend_v2/src/main/java/com/kama/jchatmind/event/listener/ChatEventListener.java`
- `@Async`
- `jChatMindFactory.create(...).run()`

面试可讲：

同步入口只负责接收和持久化，真正耗时的 Agent 推理和工具调用放到异步链路，避免请求长时间阻塞，也更方便和 SSE 联动。

### 2. 运行时 Agent 是动态组装的

`JChatMindFactory` 会按 `agentId` 和 `chatSessionId` 组装运行时上下文，主要包括：

- Agent 配置
- 最近一段会话记忆
- 当前 Agent 允许访问的知识库
- 当前 Agent 可用的工具集合
- 对应模型的 `ChatClient`

对应实现：

- `backend_v2/src/main/java/com/kama/jchatmind/agent/JChatMindFactory.java`

面试可讲：

我把运行时上下文组装和执行本身拆开了。`Factory` 负责“造 Agent”，`JChatMind` 负责“跑 Agent”，这样后续扩展模型、工具和知识库时不会把主执行类写乱。

### 3. Agent Loop 是自己接管的

`JChatMind` 核心执行结构是：

- `run()`
- `step()`
- `think()`
- `execute()`

执行逻辑：

1. `think()` 先让模型基于当前上下文做决策。
2. 如果模型返回 `toolCalls`，进入 `execute()` 手动执行工具。
3. 工具结果回填到会话上下文，再继续下一轮 `think()`。
4. 如果没有工具调用，说明可以直接结束。

对应实现：

- `backend_v2/src/main/java/com/kama/jchatmind/agent/JChatMind.java`

面试可讲：

这个项目的重点不是“接了工具”，而是我没有把工具执行完全交给框架，而是自己保留了 Loop 控制权。

### 3.1 每轮执行都带消息持久化和 SSE 推送

这条链路里，不是等整个 Agent 跑完才一次性返回结果，而是每轮 `think()` / `execute()` 都会把新消息先落库，再推给前端。

具体过程是：

1. `think()` 调模型，拿到 `AssistantMessage`
2. 先持久化 AI 消息
3. 再通过 `refreshPendingMessages()` 走 SSE 推给前端
4. 如果有 `toolCalls`，进入 `execute()`
5. `execute()` 用 `ToolCallingManager.executeToolCalls(...)` 执行工具
6. 工具结果回填 `chatMemory`
7. 再持久化 `ToolResponseMessage`
8. 再通过 SSE 把工具结果推给前端

对应实现：

- `backend_v2/src/main/java/com/kama/jchatmind/agent/JChatMind.java`

面试可讲：

我这个项目不是“模型跑完一次性返回最终答案”，而是把 Agent 执行过程拆成多轮可观测步骤。每一轮产生的新 AI 消息或工具结果，都会先持久化，再通过 SSE 推给前端，所以主链路具备可追踪、可恢复、可实时展示这几个特征。

### 4. 为什么关掉 Spring AI 默认工具自动执行

对应实现：

```java
this.chatOptions = DefaultToolCallingChatOptions.builder()
        .internalToolExecutionEnabled(false)
        .build();
```

面试可讲：

关掉默认自动执行，核心是为了自己控制 Agent Loop 边界。这样我能明确区分：

- 什么时候只是模型决策
- 什么时候真正执行工具
- 什么时候持久化消息
- 什么时候通过 SSE 往前端推

如果完全让框架内部自动执行，接入更快，但主链路可控性会差很多。

### 5. 如何避免无限循环

当前项目至少有两层控制：

- `think()` 没有返回工具调用时自然结束
- `run()` 里有 `MAX_STEPS = 20` 作为上限兜底

面试可讲：

我没有只依赖模型“自己停下”，而是加了最大步数限制，防止工具反复调用导致死循环。

# 工具调用怎么讲

### 1. 工具不是写死在 Agent 里的

工具先按类型分成两类：

- `FIXED`
- `OPTIONAL`

对应实现：

- `backend_v2/src/main/java/com/kama/jchatmind/agent/tools/ToolType.java`
- `backend_v2/src/main/java/com/kama/jchatmind/service/impl/ToolFacadeServiceImpl.java`

`JChatMindFactory` 组装运行时工具时，会先加载固定工具，再按 Agent 配置追加可选工具。

面试可讲：

固定工具负责保底能力，可选工具按 Agent 配置动态拼装，不同 Agent 拿到的工具集合不是同一份静态列表。

### 2. 业务工具要转成 Spring AI 能识别的 ToolCallback

对应实现：

- `MethodToolCallbackProvider`
- `buildToolCallbacks(...)`

面试可讲：

业务里定义的是普通 Java 工具对象，但模型侧真正识别的是 `ToolCallback`，所以运行时要做一层适配，把业务工具暴露给模型。

### 3. RAG 也是通过工具接入的

`KnowledgeTools` 是 Agent 访问知识库的统一入口，不是让 Agent 直接碰数据库。

对应实现：

- `backend_v2/src/main/java/com/kama/jchatmind/agent/tools/KnowledgeTools.java`

面试可讲：

我把“访问外部能力”统一抽象成工具，RAG 只是其中一种工具能力，这样主链路比较一致。

# RAG 怎么讲

当前链路是：

`KnowledgeTool -> RagService -> Embedding -> pgvector 相似检索`

对应实现：

- `backend_v2/src/main/java/com/kama/jchatmind/service/impl/RagServiceImpl.java`
- `backend_v2/src/main/resources/mapper/ChunkBgeM3Mapper.xml`

当前实现关键点：

- 通过 `WebClient` 调 embedding 接口
- 把向量转成 pgvector 可识别格式
- 用 `embedding <-> vector` 做相似度排序
- 当前直接返回 `topK=3` 的文本片段

这块后面我专门补过一轮离线召回评测，结论比单说“接了向量检索”更有说服力。

可以这样讲：

我先为项目建立了离线 RAG 召回基线。基线结果显示系统更偏标题相似检索，内容改写问法召回较弱。随后我将 chunk 的 embedding 表示从仅标题改为标题加正文，在同一批真实知识库和同一套评测代码下复测，`content_rewrite Recall@5` 从 `0.2883` 提升到 `0.9632`，但 `title_exact Recall@5` 从 `0.9605` 降到 `0.7727`。这说明检索能力从标题匹配明显转向了正文语义召回，也暴露了标题信号被正文稀释的问题。

这段结果最值钱的地方不是“数字变高了”，而是它能说明：

- 我不是只把 RAG 接进来，而是给它补了可量化评测
- 我能用同一套 case 做前后对比
- 我能解释为什么一个指标上升、另一个指标下降

如果面试官继续追问，我可以再补一句：

当前这套离线评测更适合做项目内优化基线，不等于行业 benchmark，因为 query case 还是从文档自动生成的，后续还可以继续补人工真实问句集。

后面我又把这条 RAG 优化继续拆到了更细的层次，不是只调一个 embedding 文本：

| 层级 | 项目里做过的优化 | 当前结论 |
| --- | --- | --- |
| 索引层 | chunk metadata 增加 `retrievableTitle/contentPath/sourceName/sourceType`；embedding 文本从普通标题内容调整为 `contentPath + title + content`；补了 trigram/tsvector 方向的索引验证 | 结构化 metadata 是后续多格式检索和路径召回的基础 |
| 查询层 | 增加 query 前置上下文补全；支持 path-aware query 自动生成 `RagRetrievalContext` | query 是否带路径/上下文，是区分同名标题 section 的关键 |
| 召回层 | 组合向量召回、标题精确匹配、contains、keyword OR、trigram、轻量 BM25、context filter | 标题候选召回已经足够，继续堆规则收益有限 |
| 重排序层 | 做了轻量 lexical rerank，把标题、正文、路径匹配分数合并排序 | 能微调排序，但不能弥补 query 本身信息不足 |
| 评测层 | 增加 coverage、Recall@1/3/5/10、MRR@3/10、miss case 对比，并区分标题、正文、路径诊断分组 | 这是最有面试价值的部分，因为能解释每轮优化为什么有效或无效 |

这轮优化最后得出的关键结论是：

- 原始内容级标题召回里，`title_to_content Recall@5 = 0.6313`
- 问题不在向量库本身，也不在继续加 BM25/trigram，而是纯 leaf title 无法区分同名 section
- 引入 `contentPath` 这类结构化路径上下文后，`contextual_title_query Recall@5 = 0.9777`
- 当 query 自带路径线索并触发自动路径选择时，`auto_path_selection Recall@5 = 1.0000`
- 正文语义召回没有退化，`content_rewrite Recall@5 = 0.9896`

面试里可以这样总结：

我没有盲目堆召回策略，而是先做离线评测，把问题拆成标题锚点召回、标题到内容 chunk 映射、正文语义召回。实验发现瓶颈不在向量模型，也不在 BM25 或 trigram，而在 query 信息不足：纯 leaf title 无法区分同名 section。于是我把文档结构抽成通用 metadata，包括 `retrievableTitle`、`contentPath`、`sourceName`、`sourceType`，并在 query 侧引入 path-aware context。最终标题到内容 chunk 的 `Recall@5` 从 `0.6313` 提升到 `0.9777` 以上，同时正文改写类 query 的召回没有退化。

这个点面试时不要讲成“我把所有 RAG 都优化完了”。更稳的边界是：

- 标题/路径召回这条线已经做得比较深入，短期不适合继续堆规则
- 当前主要解决的是文档结构化知识里的同名标题歧义问题
- 下一步如果继续做，应该转向回答质量评测、引用链路、context precision、faithfulness，而不是继续微调标题召回

再往下讲效率时，也能顺手带出来：

- RAG 不只看召回率，也要看建库效率和在线检索延迟
- 当前长文档上传慢，真正的主要瓶颈不一定是 Markdown 解析，而更可能是 chunk 后逐条调用 embedding 模型
- 因为现在是每个 section 都串行调用一次 embedding 接口，文档越长、chunk 越多，总耗时就越明显

所以这块后续的优化方向通常有两类：

- 效果优化：chunking、embedding 表示、混合检索、rerank
- 性能优化：减少 chunk 数、embedding 批处理、异步建库、阶段性进度反馈

面试可讲：

这个项目里 RAG 不是单独一条业务线，而是被 Agent 通过工具按需调用。也就是说，是否检索知识库，是模型在执行过程中动态决定的。

# 多模型切换怎么讲

对应实现：

- `backend_v2/src/main/java/com/kama/jchatmind/config/MultiChatClientConfig.java`
- `backend_v2/src/main/java/com/kama/jchatmind/config/ChatClientRegistry.java`

当前做法：

- 不同模型先注册成不同 `ChatClient Bean`
- 通过 `Map<String, ChatClient>` 收拢
- 运行时按 Agent 配置的模型标识取对应 `ChatClient`

面试可讲：

这里走的是注册表模式，不在主流程里写一堆 if-else。新增模型时通常只需要补 Bean 和配置，不需要改 Agent 主链路。

# SSE 怎么讲

对应实现：

- `backend_v2/src/main/java/com/kama/jchatmind/service/impl/SseServiceImpl.java`
- `backend_v2/src/main/java/com/kama/jchatmind/agent/JChatMind.java`

当前做法：

- 后端按 `chatSessionId` 维护 `SseEmitter`
- Agent 每次生成新消息，先持久化，再通过 SSE 推给前端
- 当前更准确的说法是“消息级实时推送”，不是 token 级 streaming

面试可讲：

SSE 主要解决的是 Agent 多步执行过程和前端展示之间的实时联动问题，让前端不需要等整条链路结束才看到结果。

# 记忆怎么讲

当前实现更偏“短期会话记忆 + 数据库存档”：

- 运行时使用 `MessageWindowChatMemory`
- 只恢复最近一段历史消息
- 用 `maxMessages` 控制上下文窗口

对应实现：

- `backend_v2/src/main/java/com/kama/jchatmind/agent/JChatMind.java`
- `backend_v2/src/main/java/com/kama/jchatmind/agent/JChatMindFactory.java`

面试可讲：

我没有把所有历史无上限塞进模型上下文，而是控制消息窗口。这样一方面控制 prompt 成本，另一方面也控制运行时内存压力。

# 并发和异步怎么讲

对应实现：

- `backend_v2/src/main/java/com/kama/jchatmind/config/AsyncConfig.java`

当前线程池配置：

- `corePoolSize = 4`
- `maxPoolSize = 10`
- `queueCapacity = 100`

面试可讲：

当前项目已经把异步事件执行和基础线程池配出来了，但它更偏学习和验证链路，不是完整生产级调度系统。这个边界要讲清楚。

# 当前最稳的几个面试亮点

### 1. 不是简单接 LLM，而是自己接管 Agent Loop

这个点比“我接了工具调用”更值钱，因为它体现的是主流程控制能力。

### 2. 工具、知识库、模型都是运行时动态组装

这说明项目不是完全写死的 demo，而是在往可扩展结构走。

### 3. RAG、多模型、SSE 已经串进同一条执行链路

不是几个孤立功能点，而是放在 Agent 主链路里联动起来了。

# 面试回答边界

当前项目可以稳讲：

- 单 Agent
- Think-Execute Loop
- 手动工具执行
- RAG 检索接入
- 多模型切换
- SSE 消息级推送
- 异步事件触发执行

当前不建议讲得太满：

- 不要说已经做了 token 级流式输出
- 不要说已经有完整多 Agent 协作
- 不要说已经有成熟生产级状态机治理
- 不要说测试体系已经完全健全

# 回答模板

后面如果你发一道强相关题，我会优先按这个结构和你一起整理：

1. 先给 30 到 60 秒面试答法
2. 再给“追问时怎么展开”
3. 最后补一句“这个点在项目里落在哪”

# 待补充题目

### 1. 什么是 Function Calling？原理是什么？

#### 面试短答

Function Calling 本质上不是让大模型真的去执行函数，而是让模型在回答过程中，按我们预先声明的函数定义，产出一份结构化的“调用意图”，通常包括函数名和参数。业务系统拿到这份调用意图后，自己去执行本地代码、RPC 或外部 API，再把结果回传给模型，模型基于结果继续回答。

一句话讲，就是：

模型负责“决定调什么、传什么参数”，程序负责“真正执行”和“把结果喂回去”。

#### 原理拆解

典型流程一般分四步：

1. 应用先把可用函数的描述、参数 schema、工具名发给模型。
2. 模型根据当前上下文判断要不要调函数，如果要调，就返回结构化调用结果，而不是直接自然语言答案。
3. 应用侧解析这份调用结果，校验参数后，真正执行对应函数。
4. 应用把函数执行结果再作为上下文回填给模型，模型再决定是继续调工具还是生成最终答案。

核心点：

- 模型不直接执行函数
- 模型只负责生成“调用决策”
- 真正执行权在业务系统
- 整个过程通常会和 Agent Loop 结合

#### 为什么它有用

因为纯 LLM 只能“生成文本”，Function Calling 让模型具备了访问外部能力的入口，比如：

- 查知识库
- 查数据库
- 发邮件
- 调天气 API
- 执行内部业务服务

所以它的本质是把“大模型推理能力”和“外部系统执行能力”接起来。

#### 在 JChatMind 里怎么落地

这个项目里，Function Calling 对应的就是 Spring AI 的 Tool Calling 机制。

关键落点有三处：

1. 暴露工具给模型

`JChatMind.think()` 里通过 `.toolCallbacks(...)` 把运行时工具暴露给模型：

- `backend_v2/src/main/java/com/kama/jchatmind/agent/JChatMind.java`

2. 工具对象适配成模型能识别的结构

`JChatMindFactory` 里通过 `MethodToolCallbackProvider` 把业务工具对象转成 `ToolCallback`：

- `backend_v2/src/main/java/com/kama/jchatmind/agent/JChatMindFactory.java`

3. 手动执行工具调用

项目里显式关闭了 Spring AI 默认的自动工具执行：

```java
this.chatOptions = DefaultToolCallingChatOptions.builder()
        .internalToolExecutionEnabled(false)
        .build();
```

然后在 `execute()` 里手动调用：

```java
ToolExecutionResult toolExecutionResult =
        toolCallingManager.executeToolCalls(prompt, this.lastChatResponse);
```

这说明当前项目里：

- 模型先返回 `toolCalls`
- 后端再真正执行工具
- 执行结果再回填到会话记忆
- Agent 再进入下一轮思考

#### 结合项目可直接讲的版本

在我的项目里，Function Calling 不是直接让模型自己执行 Java 方法，而是先把工具定义通过 Spring AI 暴露给模型。模型在 `think()` 阶段如果判断需要外部能力，就返回结构化的 `toolCalls`；后端在 `execute()` 阶段再手动调用对应工具，把结果写回上下文，然后继续下一轮决策。所以本质上，Function Calling 是“模型决策 + 应用执行”的协作机制。

#### 一版口语化表述

开发者先用 JSON Schema 一类的结构把工具描述好传给模型。模型如果判断这一步需要调用工具，就不会直接输出自然语言答案，而是返回一段结构化的 `toolCalls`，告诉后端“要调用哪个函数、参数是什么”。后端拿到这段结构化调用请求后，真正去执行本地代码或外部 API，再把执行结果塞回对话上下文里，模型最后再基于这个结果生成答案。

#### 追问时可以补一句

我这里专门把 Spring AI 的默认自动工具执行关掉了，因为我希望保留 Agent Loop 的控制权，这样更方便做最大步数限制、消息持久化和 SSE 推送。

### 2. Function Calling 解决了什么问题？

#### 面试短答

Function Calling 主要解决的是大模型只能“生成文本”，但不能稳定、安全、结构化地调用外部能力的问题。它让模型不只是会说，还能通过程序去查数据、调接口、访问知识库、执行业务动作。

#### 它主要解决四类问题

1. 解决“只能回答，不能执行”的问题

纯 LLM 更擅长生成自然语言，但它本身不能真的访问数据库、知识库、邮件系统或内部服务。Function Calling 给了模型一个调用外部能力的标准入口。

2. 解决“输出不稳定，不方便程序消费”的问题

如果只靠 prompt 让模型输出“像调用参数一样的文本”，格式很容易飘。Function Calling 会约束模型返回函数名和结构化参数，后端更容易解析和执行。

3. 解决“模型幻觉式伪执行”的问题

没有 Function Calling 时，模型可能会“声称自己查了天气、查了数据库”，但实际上并没有执行。Function Calling 把“决策”和“执行”分开，真正执行仍由后端完成，因此结果更可信。

4. 解决“外部能力接入不可控”的问题

通过函数定义、参数 schema 和后端执行层，系统可以控制模型能调用什么、不能调用什么，也方便做日志、鉴权、审计和失败处理。

#### 结合项目怎么讲

在 `JChatMind` 里，它解决的是“Agent 怎么从纯文本问答，变成可以真正调用知识库和工具”的问题。

比如：

- 没有 Function Calling 时，模型只能口头说“我去知识库查一下”
- 有了 Function Calling 后，模型会返回 `toolCalls`
- 后端再真正执行 `KnowledgeTool`、数据库工具或邮件工具
- 工具结果再回填给模型继续推理

所以项目里的 Agent 才具备了：

- 查知识库做 RAG
- 调外部工具拿真实结果
- 基于工具返回结果继续多轮决策

#### 一句更工程化的说法

Function Calling 解决的是“让大模型以可控、结构化、可执行的方式接入外部系统能力”的问题。

#### 可以顺手补一句边界

它解决的是“怎么安全稳定地调能力”，不是“调了就一定智能”。工具定义不清、参数设计差、RAG 结果噪声大，Agent 仍然可能做错决策。

### 3. Function Calling 是不是把 if-else 改成了标准化 JSON 传输？

#### 短答

可以这么理解一部分，但不能只这么讲。

更准确地说，Function Calling 做了两件事：

1. 把模型到程序之间的输出，从自由文本变成了结构化调用协议
2. 把原来业务侧手写的工具分发逻辑，收敛成统一的注册和调度机制

所以它不是简单“把 if-else 变成 JSON”，而是把“模型决策 -> 程序执行”这条链路标准化了。

#### 为什么说“只对了一半”

如果只看表面现象，确实像这样：

- 以前：让模型输出一段文本，程序自己猜它想调哪个方法，可能再手写 `if-else` 解析
- 现在：让模型按固定 schema 返回函数名和参数，程序按结构化数据执行

这个角度没有错。

但它不只是“传 JSON”：

- 重点不是 JSON 本身，而是结构化协议
- 重点不是消灭分支逻辑，而是把分支逻辑从 prompt 猜测，变成可注册、可校验、可执行的调度
- 底层仍然有分发逻辑，只是通常不再自己写一串 `if-else`，而是交给工具注册表、调度器或框架处理

#### 结合项目怎么讲

在 `JChatMind` 里，这个变化很明显：

- 模型不会再输出一段“请帮我查知识库”的自然语言让后端猜
- 模型会返回结构化的 `toolCalls`
- 后端再通过 `ToolCallingManager` 去执行对应工具

也就是说，这里不是手写：

```java
if (toolName.equals("KnowledgeTool")) {
    ...
} else if (toolName.equals("EmailTool")) {
    ...
}
```

而是走：

- 工具注册
- 工具 schema 暴露
- 统一调度执行

项目里的对应点：

- `JChatMindFactory` 用 `MethodToolCallbackProvider` 注册工具
- `JChatMind` 在 `think()` 里接收 `toolCalls`
- `JChatMind` 在 `execute()` 里通过 `ToolCallingManager.executeToolCalls(...)` 执行

#### 面试里更稳的说法

你可以说，Function Calling 确实把原来很多靠自然语言约定、甚至手写 if-else 解析的逻辑，升级成了结构化的调用协议；但它的本质不是 JSON，而是标准化了模型和业务系统之间的“工具调用接口”。

### 4. 我的项目中的 JSON 设计是怎样的？

#### 先说结论

你这个项目里的 JSON 设计，核心不是“前端随便传个 JSON”这么简单，而是把几类天然结构化、又可能变化的字段，从强耦合表结构里抽出来，统一按 JSON 存储和恢复。

当前最核心有两类：

1. Agent 运行配置 JSON
2. 聊天消息元数据 JSON

数据库层用的是 PostgreSQL `jsonb`，Java 层通过 `ObjectMapper` 在 Entity 和 DTO 之间做序列化与反序列化。

#### 1. Agent 配置 JSON

对应文件：

- `backend_v2/src/main/java/com/kama/jchatmind/model/entity/Agent.java`
- `backend_v2/src/main/java/com/kama/jchatmind/model/dto/AgentDTO.java`
- `backend_v2/src/main/java/com/kama/jchatmind/converter/AgentConverter.java`
- `backend_v2/src/main/resources/mapper/AgentMapper.xml`

`Agent` 实体里这几个字段本质上都是 JSON 字符串：

- `allowedTools`
- `allowedKbs`
- `chatOptions`

数据库落库时会：

```sql
CAST(#{allowedTools} AS jsonb)
CAST(#{allowedKbs} AS jsonb)
CAST(#{chatOptions} AS jsonb)
```

查出来时再转回文本：

```sql
allowed_tools::text
allowed_kbs::text
chat_options::text
```

然后在 `AgentConverter` 里：

- `List<String>` 会转成 JSON 数组
- `ChatOptions` 会转成 JSON 对象
- 读取时再反序列化回 `AgentDTO`

这套设计的作用是：

- Agent 可用工具列表可以动态配置
- Agent 可访问知识库列表可以动态配置
- 温度、topP、消息窗口长度这类运行参数可以结构化保存

也就是说，项目里的 Agent 不是写死在代码里的，而是“数据库里存一份 JSON 化配置，运行时再组装”。

#### 2. ChatMessage 元数据 JSON

对应文件：

- `backend_v2/src/main/java/com/kama/jchatmind/model/entity/ChatMessage.java`
- `backend_v2/src/main/java/com/kama/jchatmind/model/dto/ChatMessageDTO.java`
- `backend_v2/src/main/java/com/kama/jchatmind/converter/ChatMessageConverter.java`
- `backend_v2/src/main/resources/mapper/ChatMessageMapper.xml`

`ChatMessage` 里有个 `metadata` 字段，也是 JSON 字符串，数据库里按 `jsonb` 存。

它在 DTO 层对应的是：

- `toolCalls`
- `toolResponse`

也就是：

- Assistant 消息会把模型返回的 `toolCalls` 存进 `metadata`
- Tool 消息会把工具执行结果 `toolResponse` 存进 `metadata`

这层设计的作用是：

- 保留工具调用轨迹
- 支持会话恢复
- 支持把数据库消息重新还原成 Spring AI 的 `AssistantMessage` 和 `ToolResponseMessage`

项目里 `JChatMindFactory.loadMemory()` 就是在做这件事。

#### 3. 这套 JSON 设计的特点

你项目里的 JSON 设计，不是拿 JSON 当万能筐乱塞，而是有边界的：

- 稳定字段单独建列
  - 比如 `name`、`model`、`role`、`content`
- 易变、结构化字段放 JSON
  - 比如工具列表、知识库列表、聊天参数、工具调用元数据

这个划分是合理的，因为：

- 配置项天然是嵌套结构
- `toolCalls` / `toolResponse` 字段结构不适合硬拆表
- 后续扩展字段时对表结构冲击更小

#### 4. 面试里怎么讲最稳

你可以直接说：

我项目里 JSON 主要不是用来随便传参，而是做两类结构化信息承载。第一类是 Agent 的运行配置，比如可用工具、可访问知识库和聊天参数；第二类是消息元数据，比如模型返回的 `toolCalls` 和工具执行结果 `toolResponse`。数据库层我用 PostgreSQL `jsonb` 存，Java 层通过 `ObjectMapper` 在 Entity 和 DTO 之间转换，这样既保留了结构灵活性，也能在运行时恢复成真正的 Agent 上下文。

### 5. Function Calling 和 Agent Tool Calling 怎么区分？

#### 先给结论

Function Calling 更偏底层能力，解决的是“模型怎么以结构化方式表达调用外部函数的意图”。

Agent Tool Calling 更偏上层执行机制，解决的是“Agent 在多轮推理过程中，什么时候调用工具、调用后怎么继续往下走”。

可以把两者理解成：

- Function Calling 是协议层
- Agent Tool Calling 是编排层

#### 1. Function Calling 是什么

Function Calling 关注的是单次调用协议。

它的重点是：

- 工具怎么描述给模型
- 参数 schema 怎么定义
- 模型怎么返回结构化调用意图
- 程序怎么拿到函数名和参数去执行

它回答的是：

“模型如何调用一个外部函数？”

所以它更像一套标准接口。

#### 2. Agent Tool Calling 是什么

Agent Tool Calling 关注的是完整执行链路。

它的重点不只是一次函数调用，而是：

- 当前这一步要不要调工具
- 调完工具后要不要继续思考
- 会不会继续调下一个工具
- 什么时候结束
- 工具结果怎么进入记忆和上下文

它回答的是：

“Agent 如何把工具调用嵌进整个任务执行过程？”

所以它更像一套运行机制。

#### 3. 两者关系

更准确的关系是：

- Agent Tool Calling 通常建立在 Function Calling 之上
- Function Calling 提供结构化调用能力
- Agent Loop 决定何时调用、调用几次、何时停止

也就是说，没有 Function Calling，Agent 很难稳定调工具；但只有 Function Calling，还不等于你已经做出了一个完整 Agent。

#### 4. 结合你项目怎么讲

你这个项目里，两者其实都用了，但层次不一样。

Function Calling 体现在：

- 工具通过 Spring AI `ToolCallback` 暴露给模型
- 模型在 `think()` 阶段返回结构化的 `toolCalls`
- 后端根据调用意图执行对应工具

Agent Tool Calling 体现在：

- 你有完整的 `Think -> Execute` 循环
- `think()` 决定是否要调用工具
- `execute()` 真正执行工具并回填结果
- `run()` 负责多轮推进和 `MAX_STEPS` 兜底终止

所以更准确地说：

你的项目不是“只做了 Function Calling”，而是“用 Function Calling 作为底层调用协议，再在外面套了一层 Agent Tool Calling 执行机制”。

#### 5. 面试里最稳的说法

你可以这样讲：

Function Calling 更偏底层协议，解决的是模型怎么返回结构化的函数调用意图；Agent Tool Calling 更偏上层编排，解决的是 Agent 在多轮推理里怎么决定何时调工具、怎么处理工具结果、以及什么时候结束。我这个项目里两者都有，用 Spring AI 的 Tool Calling 做底层能力，再由 `JChatMind` 的 `Think -> Execute` 循环把它组织成完整的 Agent 执行链路。

#### 6. 一句压缩版

Function Calling 解决“怎么调”，Agent Tool Calling 解决“什么时候调、调完之后怎么办”。

### 6. LLM 是如何学会调用外部工具的？

#### 先给结论

LLM 不是天生“理解工具调用”的，它本质上还是在做下一个 token 预测。它之所以看起来会调工具，是因为训练阶段见过大量“什么时候该调用工具、该输出什么结构、拿到结果后怎么继续回答”的样本，推理阶段再结合工具定义和上下文，生成符合协议的调用结果。

一句话讲：

模型不是自己真的学会了执行工具，而是学会了在合适的时候输出“调用工具的格式”。

#### 1. 从训练角度看，它是怎么学会的

核心还是监督学习和对齐训练。

训练数据里会包含这类模式：

- 用户提问
- 模型判断这题需要外部信息
- 模型输出结构化函数调用
- 系统返回工具结果
- 模型基于工具结果生成最终答案

模型反复见过这类样本后，就会学到两件事：

1. 什么场景应该调用工具
2. 调用时应该输出什么格式

所以它学到的不是“我会查天气 API”，而是：

- 遇到天气类问题，如果有天气工具可用，就应该输出某个函数调用
- 函数名和参数要按指定 schema 来
- 工具结果回来后，再组织成自然语言答案

#### 2. 从推理角度看，它为什么现在会调用

推理时你会把两类信息给模型：

- 当前用户问题和对话上下文
- 当前可用工具的定义

模型会根据这些上下文，做一个条件判断：

- 如果只靠上下文就能答，直接自然语言回复
- 如果缺真实信息或需要外部动作，就输出结构化 `toolCalls`

所以推理阶段本质上还是“条件生成”，不是模型在代码里真的做了函数调用。

#### 3. 它真正学到的是什么

更准确地说，模型学到的是三层能力：

1. 工具选择
   - 这一步该不该用工具，用哪个工具
2. 参数构造
   - 调工具时参数该怎么填
3. 结果整合
   - 工具结果回来后，怎么把结果变成最终回答

这三层里，真正执行工具的能力并不在模型里，而在外部系统里。

#### 4. 为什么它能“像会调工具”

因为 Function Calling / Tool Calling 给了它一个固定协议。

模型并不是凭空发明工具调用格式，而是：

- 先看到工具描述
- 再按训练中学到的模式输出对应结构

你可以把它理解成：

模型不是学会了“运行函数”，而是学会了“什么时候填写一张标准化的调用单子”。

真正跑腿的是你的后端代码。

#### 5. 结合你项目怎么讲

你项目里并没有训练一个新模型去学工具调用，而是接入了已经具备 Tool Calling 能力的模型，再通过 Spring AI 把工具定义暴露给它。

项目里对应的是：

- `JChatMindFactory` 把工具对象转成 `ToolCallback`
- `JChatMind.think()` 把工具暴露给模型
- 模型返回 `toolCalls`
- `JChatMind.execute()` 再真正执行工具

所以你在项目里做的事情，不是“教会模型调用工具”，而是：

- 给模型提供可用工具集合
- 给模型运行时调用环境
- 把模型的调用意图接进自己的 Agent Loop

#### 6. 面试里最稳的说法

你可以这样说：

LLM 本质上并不是真的理解函数执行，它还是在做 token 预测。之所以会调用外部工具，是因为它在训练阶段见过大量“用户提问 -> 触发函数调用 -> 返回工具结果 -> 再组织答案”的样本，所以学会了在合适的时候输出结构化调用意图。推理时我们再把工具定义传给模型，它就能根据上下文判断是否需要调用工具。在我的项目里，我没有训练模型本身，而是把 Spring AI 的工具定义和执行链路接到了 `Think -> Execute` 循环里。

#### 7. 一句压缩版

LLM 学会的不是“执行工具”，而是“在合适的时候，按约定格式发起工具调用意图”。

### 7. 工具调用能力是在预训练学的，还是指令微调学的？

#### 先给结论

基础能力来自预训练，但真正稳定、可用的 Tool Calling 能力，主要是在指令微调和后训练阶段学出来的。

一句话讲：

- 预训练负责“会看懂语言、会学格式模式”
- 指令微调负责“学会什么时候该调工具、怎么按协议输出”

#### 1. 预训练阶段学到了什么

预训练阶段，模型会从海量文本里学到很多通用能力，比如：

- 语言理解
- 世界知识
- 代码和结构化文本模式
- JSON、XML、函数签名这类格式习惯

所以预训练之后，模型已经可能“看起来会写一段像函数调用的内容”。

但这还不够，因为它这时学到的更多是：

- 文本模式模仿
- 结构化格式生成

而不是严格意义上的“工具调用协议遵循”。

#### 2. 指令微调阶段学到了什么

真正让模型更稳定地会 Tool Calling 的，通常是指令微调和后训练。

这阶段会喂给模型大量专门样本，比如：

- 什么问题该直接回答
- 什么问题该调用工具
- 该选哪个工具
- 参数怎么填
- 工具结果回来后怎么继续回答

所以模型会被进一步对齐成：

- 遇到查询类问题优先走工具
- 按指定 schema 输出
- 减少乱编和格式漂移

#### 3. 更完整一点的说法

如果面试官追问得更细，你可以说：

- 预训练提供语言和模式基础
- SFT 让模型学会工具调用样例
- 后训练或偏好对齐进一步提升稳定性、遵循性和错误率表现

所以稳定 Tool Calling 通常不是单靠预训练得到的，而是后训练重点打磨出来的能力。

#### 4. 结合你项目怎么讲

你项目里并没有自己训练模型，而是直接使用已经具备 Tool Calling 能力的模型，然后通过 Spring AI 暴露工具定义，让模型在运行时使用这项能力。

所以你项目讨论的是：

- 怎么接入这种能力
- 怎么控制执行流程

不是：

- 怎么训练出这种能力

#### 5. 面试里最稳的说法

你可以这样讲：

Tool Calling 的基础格式感和语言理解能力，底子来自预训练；但真正让模型稳定学会“什么时候调用工具、如何按 schema 输出、工具结果回来后怎么继续回答”，主要还是靠指令微调和后训练阶段。所以我更倾向于说，工具调用能力是“预训练打底、后训练定型”。

### 8. 为什么有的模型 Tool Calling 很稳，有的很飘？

#### 先给结论

因为 Tool Calling 稳不稳，本质上取决于模型有没有被充分训练去遵守工具调用协议，以及它本身的推理能力、格式遵循能力和上下文稳定性够不够。

#### 1. 训练数据和后训练质量不同

这是最核心的原因。

如果一个模型在后训练阶段见过大量高质量工具调用样本，它通常会更稳：

- 更容易选对工具
- 更容易填对参数
- 更少乱输出自然语言
- 更少 schema 漂移

反过来，如果样本少、质量差，模型就容易飘。

#### 2. 模型的格式遵循能力不同

有些模型天然更擅长严格按格式输出，比如：

- JSON 更闭合
- 字段名更稳定
- 参数层级更不容易错

有些模型虽然聊天能力不错，但结构化输出不稳定，Tool Calling 体验就会差。

#### 3. 模型的推理和决策能力不同

Tool Calling 不只是“会输出格式”，还包括：

- 这一步到底该不该调工具
- 该调哪个工具
- 参数该怎么构造

如果模型推理能力弱，就算格式能写对，也可能：

- 选错工具
- 漏填参数
- 本来该调工具却直接瞎答

#### 4. 上下文窗口和长链路稳定性不同

工具调用往往不是单轮结束，尤其在 Agent 场景里会经过：

- 用户问题
- 工具定义
- 历史消息
- 工具结果
- 再次推理

有些模型一旦上下文变长，稳定性就会明显下降，更容易出现：

- 忘记工具定义
- 混淆参数
- 工具结果整合错误

#### 5. 工具 schema 和描述写法也会影响表现

这不是模型单方面的问题。

如果工具定义本身就写得含糊，也会导致模型飘，比如：

- 工具职责重叠
- 描述不清楚
- 参数命名模糊
- 缺少约束

所以同一个模型，在不同工具设计下，表现也会差很多。

#### 6. 推理参数和执行框架也会影响稳定性

比如：

- `temperature` 太高，输出更容易发散
- 自动执行链路太黑盒，问题难定位
- 没有参数校验和失败兜底，错误会被放大

这也是为什么你的项目里手动接管了 Tool Calling 执行流程，会更容易观察问题和控制边界。

#### 7. 结合你项目怎么讲

你这个项目里，模型稳不稳会直接影响三件事：

- `think()` 阶段会不会正确返回 `toolCalls`
- `execute()` 阶段能不能拿到可执行参数
- 整个 `Think -> Execute` 循环会不会很快收敛

所以如果模型 Tool Calling 不稳，你的 Agent 就会出现：

- 乱调工具
- 不该调时乱调
- 参数错误
- 明明该结束却继续循环

#### 8. 面试里最稳的说法

你可以这样讲：

有的模型 Tool Calling 很稳，核心是因为它在后训练阶段被充分训练去遵守工具调用协议，同时它本身的格式遵循能力、推理能力和长上下文稳定性也更强。反过来，如果模型只是会聊天，但结构化输出、工具选择和参数构造能力不够强，就很容易飘。另外，工具 schema 设计和运行参数也会影响最终表现，所以这既是模型能力问题，也是工程设计问题。

#### 9. 一句压缩版

Tool Calling 稳不稳，取决于模型后训练质量 + 结构化输出能力 + 推理能力 + 工具设计质量。

# 协议扩展

### 9. MCP 协议和 A2A 协议是什么，有什么关系和区别？

#### 先给结论

你的理解大方向是对的：

- MCP 主要解决“Agent/LLM 怎么标准化连接工具和外部资源”
- A2A 主要解决“一个 Agent 怎么和另一个 Agent 协作”

但面试里要讲得更准确一些，因为两者都不只是“发个 JSON”这么简单。

#### 1. MCP 是什么

MCP 全称是 Model Context Protocol。

它可以类比成 AI 应用连接外部能力的标准接口层，USB-C 这个比喻是可以用的，但最好补一句：它不是只定义一个插口，而是还定义了连接建立、能力声明、消息结构和部分会话规则。

MCP 官方定义里，不只是工具，还包括：

- tools
- resources
- prompts
- client/server capability
- lifecycle
- authorization

协议底层使用的是 JSON-RPC 2.0，但不能只说成“JSON + RPC”，因为 MCP 还有更完整的协议层设计，比如生命周期、能力协商和鉴权框架。

#### 2. A2A 是什么

A2A 全称是 Agent2Agent Protocol。

它面向的是独立 Agent 之间的协作，不只是“发消息聊天”，而是完整的 Agent 间任务协作协议。

它强调的能力包括：

- Agent 发现
- 能力声明
- 任务创建与跟踪
- 多轮上下文协作
- 流式更新
- 认证与授权

其中一个关键概念是 `Agent Card`，可以理解成 Agent 的公开能力说明书，方便别的 Agent 发现它、理解它支持什么能力、需要什么认证。

#### 3. 两者的核心区别

最本质的区别是交互对象不同。

MCP 面向的是：

- Agent/LLM <-> 工具 / 资源 / 上下文能力

A2A 面向的是：

- Agent <-> Agent

所以：

- MCP 更像“调用能力”
- A2A 更像“协作任务”

#### 4. 再往深一层的区别

MCP 的典型对象通常是相对明确、结构化、偏工具型的能力，比如：

- 数据库查询
- 文件系统访问
- 搜索接口
- 本地 IDE 能力

A2A 的典型对象则是更自主的系统，比如一个旅行规划 Agent、一个采购 Agent、一个客服 Agent。

这类对象的特点不是只接一次请求就结束，而是可能：

- 需要多轮沟通
- 维护更长的任务状态
- 拥有自己的记忆和工具链
- 以“任务伙伴”的角色协作

#### 5. 两者的关系

两者不是竞争关系，而是互补关系。

更准确的理解是：

- A2A 管 Agent 之间怎么协作
- MCP 管 Agent 内部怎么接工具和资源

一个很常见的组合方式是：

- Agent A 通过 A2A 把任务委托给 Agent B
- Agent B 在内部再通过 MCP 去调用数据库、搜索、文件系统等工具

官方文档也是把它们定义成互补协议，而不是替代协议。

#### 6. 你这版回答里要修正的地方

第一，MCP 不只是“所有工具的统一协议”。

更准确地说，它是 LLM/Agent 与工具、资源、提示和上下文能力之间的标准化协议，不只有工具。

第二，不要只说“JSON+RPC”。

更准确是：

- MCP 的消息层基于 JSON-RPC 2.0
- A2A 也可以基于 JSON-RPC 2.0 over HTTP(S)，并支持流式更新

所以 JSON-RPC 不是 MCP 独有标签，A2A 也能用。

第三，A2A 不只是“Agent 间通讯”。

更准确是 Agent 间协作协议，因为它除了通讯，还包括：

- 发现对方
- 声明能力
- 管理任务
- 跟踪状态
- 流式返回进度

#### 7. 结合你项目怎么讲

你当前的 `JChatMind` 项目，并没有真正落地 MCP 或 A2A 协议。

当前更接近的是：

- 基于 Spring AI 的本地 Tool Calling
- 自己实现的单 Agent 执行闭环

所以如果面试官追问“你项目里用了 MCP 或 A2A 吗”，更稳的回答是：

还没有直接接这两个协议。当前项目重点是先把单 Agent 的 Tool Calling、RAG、SSE 和执行闭环跑通；如果后续要扩展到标准化工具接入，可以往 MCP 演进；如果要做多 Agent 协作，可以考虑引入 A2A。

#### 8. 面试里最稳的说法

你可以这样讲：

MCP 和 A2A 是两个互补的协议。MCP 更偏 Agent 或 LLM 连接工具、资源和上下文能力的标准化协议，底层基于 JSON-RPC 2.0，但还包含生命周期、能力协商和鉴权等设计；A2A 更偏 Agent 与 Agent 之间的协作协议，除了消息传递，还支持 Agent 发现、能力声明、任务管理和流式状态更新。简单说，MCP 解决“怎么接能力”，A2A 解决“怎么和另一个 Agent 协作做任务”。

#### 9. 一句压缩版

MCP 是 Agent 连工具和资源的协议，A2A 是 Agent 和 Agent 协作的协议；两者互补，不是替代。

# 高频追问速背版

### Agent Loop

#### 1. 你设计的 Think-Execute 循环，本质上解决了什么问题？

它把“模型决策”和“系统执行”拆开了，让工具调用、持久化、SSE 推送和终止控制都能放进一条可治理的执行链路里。没有这层循环，就更像一次普通 LLM 调用，而不是 Agent。

#### 2. Think-Execute 和 ReAct、Plan-and-Execute 有什么区别？

你这个实现更接近工程化的 ReAct，也就是边想边做；`think()` 负责决策，`execute()` 负责行动。它不是典型 Plan-and-Execute，因为你没有先产出全局计划再按计划执行。

#### 3. 为什么要关闭 Spring AI 默认工具自动执行？默认方式的风险是什么？

关闭默认自动执行，是为了拿回 Agent Loop 控制权。默认方式的问题是执行过程更黑盒，持久化、SSE 推送、终止和审计时机都不容易精确控制。

#### 4. 你是怎么防止 Agent 死循环的？终止条件有哪些？

当前有四层：没有 `toolCalls` 时自然结束、显式 `terminate` 工具结束、`MAX_STEPS=20` 兜底结束、异常进入 `ERROR` 结束。你现在还没做重复调用检测和语义无进展检测，这点要如实说。

### 工具体系

#### 5. 什么叫固定工具和可选工具？为什么要区分这两类？

固定工具是所有 Agent 都应该具备的保底能力，可选工具是按 Agent 配置动态挂载的业务能力。这样做是为了把系统基础能力和业务权限能力分开，符合最小权限原则。

#### 6. MethodToolCallbackProvider 在这里起了什么作用？底层思路是什么？

它负责把你写的 Java 工具对象转换成 Spring AI 可识别的 `ToolCallback`。底层思路就是扫描带 `@Tool` 注解的方法，提取工具名、描述和参数结构，再封装成模型可调用的工具定义。

#### 7. 模型是怎么“知道”有哪些工具可以调用的？

不是模型自己知道，而是你在 `think()` 里通过 `.toolCallbacks(...)` 主动暴露给它的。Spring AI 会把工具定义带进请求里，模型才会知道当前有哪些工具可选。

### 多模型

#### 8. 为什么要做 ChatClientRegistry，而不是把模型切换逻辑写死？

因为注册表模式比 `if-else` 更可扩展。新增模型时只要加 Bean 和配置，不需要改主流程代码，也更适合按 Agent 配置动态切换模型。

#### 9. 多模型接入时，怎么统一不同模型的参数、返回格式和流式能力？

你现在主要通过 `ChatClient` 和 `ChatResponse` 做了调用入口和结果结构统一。参数透传和 token 级流式能力目前还没完全统一，当前实际落地的是消息级 SSE 推送。

### RAG

#### 10. RAG 的完整链路是什么？从用户问题到最终回答，中间经过哪些步骤？

用户问题进入 `think()` 后，如果模型判断缺上下文，就调用 `KnowledgeTool`；`KnowledgeTool` 再调 `RagService` 做 embedding、pgvector 检索、返回 topK 片段，最后工具结果回写上下文，下一轮 `think()` 再基于检索结果生成回答。

#### 11. Embedding 的作用是什么？为什么向量相似度能支持语义检索？

Embedding 把文本映射到语义向量空间里，让语义相近的文本在空间里距离也更近。向量数据库不是自己懂语义，而是语义已经被 embedding 模型编码进向量里了。

#### 12. 为什么选 PostgreSQL + pgvector，而不是单独的向量数据库？

因为当前项目更看重统一技术栈和实现复杂度，用 PostgreSQL 可以同时管结构化数据和向量数据，开发运维成本更低。它不是最极致性能方案，但对当前项目规模是够用的。

#### 13. pgvector 支持哪些距离度量？cosine、L2、内积分别适合什么场景？

常见有 cosine、L2、内积；你项目当前实际用的是 `<->`，也就是 L2 距离。一般语义检索更常讲 cosine，L2 适合直接比较欧氏距离，内积常见于推荐和向量召回。

#### 14. chunk size、overlap、topK 怎么定？它们会影响什么？

它们决定召回结果的完整性、噪声和上下文成本：chunk 太大噪声多，太小上下文断裂，topK 太大噪声高、太小容易漏。你当前项目是 Markdown 章节级切分，`topK` 目前写死为 3，还不是完整可调的生产方案。

如果再结合我实际做过的优化去讲，可以补一句：

我后来专门给项目补了离线召回评测，先用真实知识库建立 baseline，再调整 chunk 的 embedding 表示，从“仅标题”改成“标题 + 正文”，最后用同一套 case 对比 `title_exact` 和 `content_rewrite` 两类召回结果。这样就不是凭感觉调 chunk，而是能量化看优化到底把检索能力推向了“标题匹配”还是“正文语义召回”。

#### 15. 多知识库检索怎么做隔离、过滤和结果合并？

你当前已经做了知识库隔离：Agent 配置里有 `allowedKbs`，工具调用时又按 `kbId` 定向检索。真正的多知识库联合召回和统一 rerank 目前还没落地。

#### 15.1 RAG 的执行效率是不是重要指标？长文档处理慢通常慢在哪里？

是，RAG 不只看召回率，执行效率也是重要指标，而且最好拆成两段看：

- 离线建库效率：上传、解析、切 chunk、生成 embedding、落库
- 在线检索效率：query embedding、向量检索、拼接上下文、返回结果

我当前项目里，长文档处理慢更可能不是 Markdown 解析本身慢，而是 chunk 后逐条调用 embedding 模型慢。因为现在每个 section 都会单独调用一次 embedding 接口，文档越长、标题越多、chunk 越多，总耗时就越明显。

所以如果后续继续优化，这块通常有几个方向：

- 控制 chunk 数量，避免切得过碎
- 优化 chunk 文本构成，减少无效 embedding
- 做 embedding 批处理或异步建库
- 给前端增加建库状态和进度反馈

#### 15.2 RAG 有哪些评估指标？如果 RAG 效果差，有什么优化方案？

#### 先给结论

RAG 评估不要只看一个召回率，至少要分三层：

- 检索质量
- 生成质量
- 工程指标

如果 RAG 效果差，也不要一上来就换模型，先判断问题到底在：

- 根本没召回到
- 召回到了但排序差
- 召回结果噪声太大
- 生成阶段没用好检索结果
- 用户 query 本身信息不足

不同问题，优化方向完全不一样。

#### 1. 常见评估指标有哪些

第一类是检索层指标，也就是“正确 chunk 有没有被找回来”：

- `Recall@K`
- `HitRate@K`
- `Coverage`
- `MRR@K`
- `Precision@K`
- `NDCG@K`
- miss case 分析

这些指标里：

- `Recall@K` 看前 K 个结果里有没有召回 gold chunk
- `MRR@K` 看正确结果排得靠不靠前
- `Precision@K` 看前 K 个结果噪声多不多
- miss case 分析看失败样本集中在哪类 query

第二类是生成层指标，也就是“最后答案是不是基于检索结果答对了”：

- answer correctness
- faithfulness / groundedness
- answer relevance
- citation accuracy

这层解决的是：

- 是否答对
- 是否引用对
- 是否基于检索内容回答
- 有没有幻觉

第三类是工程层指标，也就是“这套 RAG 跑得稳不稳、快不快”：

- 建库耗时
- 在线检索延迟
- `P95 / P99`
- embedding 调用次数
- 失败率、超时率

#### 2. 你项目里当前重点在看哪些指标

你这个项目当前最成熟的是检索评测，已经不只是口头说“接了 RAG”，而是有离线基线和线上入口评测。

当前已经实际在看的指标包括：

- `coverage`
- `Recall@1/3/5/10`
- `MRR@3/10`
- miss case 对比
- 按 query 类型分组评测
  - `title_exact`
  - `content_rewrite`

另外你还补了：

- 线上 E2E 检索测试
- session context 多轮追问测试

这点很加分，因为它说明你不是凭感觉调参数，而是先做基线，再做定量优化。

#### 3. 如果 RAG 效果差，先怎么定位

更稳的排障顺序是：

第一步，先看 `Recall@K`

- 如果 `Recall@K` 很低，说明根本没召回到，问题主要在召回层

第二步，再看 `MRR@K` 或 `Recall@1`

- 如果 `Recall@K` 不低，但 `MRR` 很差，说明召回到了，但排序不行

第三步，再看最终回答

- 如果召回和排序都还可以，但答案还是差，问题更可能在上下文噪声、prompt 设计或者生成阶段

第四步，看 miss case 模式

- 如果失败样本集中在纯标题问法、缩写问法、追问问法，很多时候不是 embedding 不行，而是 query 信息本身不够

#### 4. 常见优化方案有哪些

如果问题在“没召回到”，常见优化有：

- 调整 `chunk size` 和 `overlap`
- 优化 chunk 的 embedding 文本构成
- 给 chunk 增加结构化 metadata
  - `contentPath`
  - `sourceName`
  - `sourceType`
  - `retrievableTitle`
- 混合检索
  - 向量召回
  - 标题精确匹配
  - BM25 / 全文检索
  - trigram
- 做 query rewrite、HyDE、多 query 融合
- 做 kb 级过滤和路由

如果问题在“召回到了但排位差”，常见优化有：

- 提高候选召回数
- 增加 rerank
- 把标题、正文、路径等信号一起参与排序
- 按 query 类型拆策略

如果问题在“答案生成差”，常见优化有：

- 调整 `topK`
- 降低上下文噪声
- 做 chunk 去重和合并
- 改回答 prompt，强约束基于检索结果回答
- 增加引用约束，降低幻觉

如果问题在“query 信息不足”，常见优化有：

- 给 query 补路径上下文
- 利用 session 上下文做 retrieval context
- 引导用户补来源或标题路径
- 必要时先做候选路径选择，而不是继续堆召回规则

#### 5. 结合你项目，比较有代表性的优化思路

你项目里最值得讲的不是“我调了个 topK”，而是你把问题拆开做了。

比如：

- 先做离线召回基线，不凭感觉调参
- 把 query 分成 `title_exact` 和 `content_rewrite` 两类评测
- 把 chunk embedding 表示从“仅标题”改成“标题 + 正文”
- 发现 `content_rewrite Recall@5` 明显提升，但 `title_exact Recall@5` 回落

这个结果很重要，因为它说明：

- 优化不是单向变好
- 而是在“标题匹配”和“正文语义召回”之间发生了偏好切换

你后面继续做的优化方向也比较成体系：

- `contentPath + title + content`
- 标题锚点召回
- path-aware query
- retrieval context
- 轻量 lexical rerank

这套过程比单纯说“换了个 embedding 模型”更有说服力。

#### 6. 你项目里一个很关键的经验

你这个项目已经证明了一个很典型的问题：

有些 RAG 效果差，不是向量模型差，也不是数据库检索差，而是 query 本身信息不足。

比如纯 leaf title 问法，如果知识库里有很多同名 section，那么系统即使已经能稳定召回标题锚点，也未必能稳定定位到具体内容 chunk。

这时候继续堆数据库规则，收益会越来越低；更合理的方向反而是：

- 补 `contentPath`
- 补 session 上下文
- 做 query 前置上下文补全
- 或者让用户先选候选路径

这个判断很有面试价值，因为它说明你知道什么时候该继续优化检索，什么时候该承认问题出在输入信息不足。

#### 7. 面试里最稳的说法

你可以这样讲：

RAG 评估我一般分三层。第一层是检索质量，重点看 `Recall@K`、`MRR@K`、coverage 和 miss case，判断正确 chunk 有没有被召回以及排位是否靠前；第二层是生成质量，重点看答案正确性、faithfulness 和引用是否准确；第三层是工程指标，比如建库耗时、查询延迟和稳定性。如果效果差，我不会直接换模型，而是先定位问题到底在召回、重排、上下文噪声还是生成阶段。像我这个项目里，就专门做过离线召回评测，把 query 分成 `title_exact` 和 `content_rewrite` 两类，再分别从 chunk 表示、metadata、混合检索、query rewrite 和 session context 这些方向去优化。

#### 15.3 token 和 query 有什么区别？项目中的 RAG 怎么处理切片的？

#### 先给结论

- `query` 是业务层概念，表示“用户拿来检索的一整段问题”
- `token` 是模型层概念，表示文本进入模型后被切开的最小处理单位

两者不是一层东西。

更准确地说：

- `query` 是输入内容
- `token` 是输入被模型编码后的内部单位

你这个项目当前的 RAG 切片，也不是按 token 长度切的，而是按 Markdown 章节结构切的。

#### 1. token 和 query 的区别

举个最简单的例子：

`面试时如何回答自己的优缺点？`

这整句话，在 RAG 里首先是一个 `query`。

但它送进 embedding 模型或大模型之后，会再被拆成很多 `token` 去计算。

所以关系是：

- query 是完整检索请求
- token 是模型处理 query 时用的最小单元

项目里你真正手动处理的是 `query`，不是 token。

比如当前链路里：

- `KnowledgeTools.knowledgeQuery(kbsId, query)`
- `ragService.retrieve(kbId, query, retrievalContext, 3)`

这里传来传去的都是 query 文本。

#### 2. 你项目里的 query 是怎么走的

当前检索链路不是“拿原始 query 直接查向量库”，而是先做一层查询处理。

流程大致是：

1. Agent 决定调用 `KnowledgeTool`
2. `KnowledgeTools` 读取当前 session 的 `retrievalContext`
3. 调 `ragService.retrieve(kbId, query, context, 3)`
4. `RagServiceImpl` 先调用 `QueryRewriteService.rewrite(...)`
5. 再对 rewrite 后的 query 做 embedding
6. 然后走向量召回、标题精确匹配、contains、keyword、trigram、BM25
7. 最后做 context filter 和 rerank

所以你项目里的 query，不是裸查，而是“查询文本 + 上下文补全 + 多路召回 + 重排”。

#### 3. 你项目里的 RAG 是怎么切片的

当前不是 token-based chunking，而是 Markdown 章节级切片。

也就是说：

- 先解析 Markdown
- 每遇到一个标题，就形成一个 section
- 当前标题到下一个标题之间的内容，归到这个 section
- 每个 section 生成一个 chunk

所以你现在的 chunk 粒度，本质上是：

- 一个标题
- 该标题下的一段正文
- 再加一条结构化路径 `contentPath`

#### 4. 具体怎么实现

你项目里文档上传后，如果文件类型是 Markdown，会走：

- `DocumentFacadeServiceImpl.processMarkdownDocument(...)`

这一步会：

- 调 `MarkdownParserService.parseMarkdown(...)`
- 把文档解析成多个 `MarkdownSection`
- 再遍历 sections，为每个 section 生成一个 chunk

而 `MarkdownParserServiceImpl` 里的策略是：

- 只遍历文档顶层节点
- 识别所有标题节点
- 当前标题到下一个标题之间的内容，都归到当前 section
- 同时构造 `contentPath`

所以它保留的是文档结构语义，不是简单按固定字符数硬切。

#### 5. 每个 chunk 里保存了什么

你现在每个 chunk 不只是正文，还会保存 metadata，包括：

- `title`
- `retrievableTitle`
- `retrievableTitleSearchText`
- `contentPath`
- `sourceType`
- `sourceName`
- `sectionIndex`

这也是你后面能做这些能力的基础：

- 标题精确召回
- path-aware query
- session retrieval context
- rerank

#### 6. embedding 文本是怎么构造的

你项目里不是只拿正文去做 embedding。

当前构造方式是：

- 如果有 `contentPath`，就用 `contentPath + "\\n" + title + "\\n" + content`
- 如果正文为空，就退化成标题或路径本身

这点很关键，因为它说明你的 chunk 表示不是纯正文向量，而是把：

- 路径
- 标题
- 正文

一起编码进 embedding。

这也是为什么你后面能把标题召回、路径召回和正文语义召回分开诊断。

#### 7. 这套切片方案的特点和边界

优点是：

- 简单直接
- 贴合 Markdown 文档结构
- 标题语义保留得比较好
- 天然适合做 `contentPath` 路径召回

边界是：

- 当前不是按 token 长度严格控 chunk
- 没有通用 overlap 机制
- 某些特别长的章节，可能会形成偏大的 chunk
- 当前长文档性能瓶颈更主要在“每个 section 都单独调一次 embedding”

#### 8. 面试里最稳的说法

你可以这样讲：

在 RAG 里，query 是用户拿来检索的一整段问题，token 是这段文本进入模型后被切开的最小处理单位，两者不是一个层级。我这个项目里实际手动处理的是 query，而不是 token。当前 RAG 切片也不是按 token 数硬切，而是按 Markdown 标题做章节级切片：每个标题及其下方正文形成一个 section，再把 `contentPath + title + content` 一起做 embedding。这样做的好处是保留了文档结构语义，方便后面做标题召回、路径召回和基于 session context 的检索优化。

#### 15.4 为什么你的项目没有按 token 切 chunk，这样会有什么利弊？

#### 先给结论

你这个项目当前没有按 token 长度切 chunk，不是因为不能做，而是因为当前知识源主要是 Markdown 文档，章节结构本身就是很强的语义边界。

所以你现在优先选的是：

- 结构驱动切片
- 而不是长度驱动切片

更具体地说，就是：

- 按标题切 section
- 当前标题到下一个标题之间的内容归到这个 section
- 再把 `contentPath + title + content` 一起做 embedding

#### 1. 为什么当前方案合理

对你这类知识库文档来说，Markdown 标题天然就是主题边界。

比如八股文档、面试题整理、知识笔记，通常都是：

- 一级标题代表大主题
- 二级标题代表子问题
- 标题下面的正文就是这个问题的解释

这种材料如果直接按 token 长度硬切，容易出现：

- 标题和正文被切开
- 同一个问题的上下文被拆散
- 检索到的 chunk 不再保留完整章节语义

所以你当前优先保留的是文档结构，而不是严格的长度均匀性。

#### 2. 这套方案的优点

主要优点有四个：

- 标题和正文关系保留得更完整
- 天然适合 `contentPath` 路径召回
- 更适合做标题锚点、path-aware query、session context
- 实现简单，调试和解释都更直接

尤其在你项目里，后面很多优化都依赖这一点：

- `retrievableTitle`
- `contentPath`
- `sourceName`
- `sourceType`

如果一开始就把文档纯按 token 打碎，这些结构信号会弱很多。

#### 3. 这套方案的缺点和边界

它也不是没有代价。

当前方案的主要边界是：

- chunk 长度不均匀
- 没有通用 overlap
- 某些特别长的 section 会形成偏大的 chunk
- embedding 可能被长正文稀释
- 对非结构化文档不一定适合

这也是为什么你前面做离线评测时，后来会发现：

- 标题信号和正文语义信号之间会互相拉扯
- 有些优化会提升 `content_rewrite`
- 但会让 `title_exact` 回落

也就是说，这套切片更强调结构语义，不强调长度绝对稳定。

#### 4. 如果以后要继续演进，方向是什么

后面如果继续增强，可以考虑的是：

- 在章节级切片基础上增加长度上限
- 对超长章节再做二次切片
- 补 overlap
- 按文档类型区分 chunking 策略

更稳的演进方向不是直接把当前方案推翻，而是：

- 先保留结构化 chunking
- 再对极端长 section 做长度兜底

这样能兼顾：

- 结构语义
- chunk 大小控制

#### 5. 面试里最稳的说法

你可以这样讲：

我这个项目当前没有按 token 长度切 chunk，主要是因为知识源以 Markdown 文档为主，章节标题本身就是天然的语义边界。相比固定 token 窗口，我更希望保留标题、正文和路径之间的结构关系，所以先用了章节级切片，再把 `contentPath + title + content` 一起做 embedding。这样做的好处是更适合标题召回、路径召回和多轮上下文检索；缺点是 chunk 长度不够均匀，超长 section 可能带来噪声，后续如果继续演进，可以在章节级切片之上再加长度上限和 overlap。

#### 15.5 rerank 和向量召回分别解决什么问题？

#### 先给结论

一句话讲：

- 向量召回解决“先把可能相关的候选找出来”
- rerank 解决“把这些候选重新排对顺序”

所以两者不是替代关系，而是串联关系。

#### 1. 向量召回解决什么问题

向量召回更偏“粗召回”。

它主要负责：

- 从大库里先找出一批可能相关的 chunk
- 处理字面不一致但语义相近的 query
- 尽量降低漏召回

也就是说，它更关注：

- recall
- 候选覆盖率

没有这一步，系统容易出现：

- 用户换个问法就找不到
- 只能做字面匹配
- 改写问法召回弱

#### 2. rerank 解决什么问题

rerank 更偏“精排序”。

它解决的是：

- 候选已经找到了，但顺序不够准
- 哪个 chunk 应该排第一
- 标题信号、路径信号、上下文信号怎么补进排序

也就是说，它更关注：

- `Recall@1`
- `MRR`
- top1 / top3 的命中质量

如果没有 rerank，常见问题是：

- 正确 chunk 在候选里，但排位靠后
- 标题和路径特别贴近的 chunk 反而排不过纯语义近似结果

#### 3. 结合你项目怎么讲

你项目里的做法已经不是单一路径了，而是：

- 先做向量召回
- 再补标题精确匹配、contains、keyword、trigram、BM25 这些 lexical 候选
- 候选合并后，再做 rerank

rerank 里实际参考了很多信号：

- 标题精确匹配
- 标题包含
- 标题 overlap
- 正文 overlap
- `contentPath`
- `sourceName`
- context 命中
- 原始 rank penalty

所以你这套系统里：

- 向量召回负责把相关候选尽量找全
- rerank 负责把标题、正文、路径、上下文这些更细的信号组合起来，把顺序排稳

#### 4. 面试里最稳的说法

你可以这样讲：

向量召回和 rerank 解决的不是同一个问题。向量召回负责从大规模候选里先把语义相关的 chunk 找出来，重点是 recall；rerank 负责对这些候选再排序，重点是 precision 和首位命中质量。在我的项目里，向量召回主要承担语义召回，rerank 则把标题、正文、路径和上下文这些更细的信号一起合并，用来提升 `Recall@1` 和 `MRR`。所以两者是前后串联的，不是二选一。

#### 15.6 session retrievalContext 为什么能提升多轮追问效果？

#### 先给结论

它之所以有效，不是因为 embedding 变强了，而是因为它补足了多轮追问里“第二问信息不完整”的问题。

一句话讲：

- 第一轮命中后，系统知道你在聊哪一段
- 第二轮追问虽然说得很短，但上下文已经帮你把检索空间收窄了

#### 1. 多轮追问的问题本质是什么

多轮追问里，第二问往往信息量很低。

比如第一轮已经在某个章节下了，第二轮用户可能只会问：

- `回答 面试怎么回答`
- `这里怎么说`
- `这一段怎么理解`

这种 query 单独拿出来看，问题很明显：

- 没有文档名
- 没有路径
- 没有父标题
- 甚至可能只有一个低信息量 leaf title

所以无状态检索时，系统天然很难知道你到底在追问哪一段。

#### 2. 你项目里 session retrievalContext 做了什么

你当前的做法是：

- 第一轮 `KnowledgeTools` 检索完后
- 从 top1 结果里提取结构化信息
- 重点保留 `sourceType`、`sourceName`、父级 `contentPath`
- 把这些信息持久化到 chat session

第二轮再检索时：

- 先从 session 里取 `retrievalContext`
- 再带着这个 context 进入 `ragService.retrieve(...)`
- `QueryRewriteService` 和 `RagServiceImpl` 都会利用这个 context 做过滤、约束和重排

所以第二轮不是“裸 query”，而是：

- follow-up query
- 加上一轮留下来的结构化上下文

#### 3. 为什么它能显著提升效果

它主要带来两个收益：

第一，缩小候选空间

- 不再是全库无约束检索
- 而是优先落在当前文档、当前路径附近

第二，补足 query 缺失信息

- 用户没说清楚的那部分，由 session context 补回来了

所以很多原本无状态下完全无解的 follow-up query，在带 context 后就能稳定收敛。

#### 4. 你项目里这点为什么特别有说服力

因为你不是只做了功能，还专门做了 session-aware 评测。

而且你文档里已经有一个很关键的结论：

- `session retrievalContext` 对多轮追问场景有真实收益
- 而且不是轻微优化
- 是从完全 miss 提升到稳定命中

这点非常值钱，因为它说明收益来源不是“我感觉更智能了”，而是：

- 第一轮命中后，把 `parent contentPath` 存进会话
- 第二轮低信息量 query 再检索时，系统能基于上下文精确收敛

#### 5. 面试里最稳的说法

你可以这样讲：

`session retrievalContext` 能提升多轮追问效果，核心原因不是模型更强，而是它解决了 follow-up query 信息不足的问题。第一轮检索命中后，我会把 top1 结果里的 `sourceName`、`sourceType` 和父级 `contentPath` 持久化到当前会话；第二轮用户再问“这里怎么回答”“这一段怎么讲”这类低信息量问题时，系统就不是做无状态全库检索，而是带着上一轮的结构化上下文去做过滤、重写和排序。这样检索空间会明显收窄，很多原本完全 miss 的追问场景就能稳定命中。

### SSE

#### 16. 为什么这里选 SSE，不选 WebSocket？

因为你当前主要是服务端单向推送 Agent 结果，而不是双向高频交互。SSE 基于 HTTP，接入简单，和当前聊天结果实时展示场景更匹配。

#### 17. SseEmitter 如何处理断连、超时、并发推送和资源释放？

你当前用 `ConcurrentHashMap` 管理会话级 emitter，30 分钟超时，并在 `onCompletion/onTimeout/onError` 时移除连接，避免资源泄漏。并发连接表是线程安全的，但你现在还没做重试、心跳和离线补发。

#### 17.1 SSE 是什么，项目中怎么用的，是实时的吗？

#### 先给结论

SSE 全称是 `Server-Sent Events`，本质上是基于 HTTP 长连接的服务端单向推送机制。

你这个项目里，它主要用来把 Agent 运行过程中新产生的消息实时推给前端聊天界面。

但这里要讲准：

- 它是实时推送
- 但当前是“消息级实时”
- 不是“token 级流式输出”

#### 1. SSE 是什么

SSE 可以理解成：

- 前端先和后端建立一个长连接
- 后端后续持续往这个连接里推事件
- 前端不需要轮询就能收到新消息

它更适合这种场景：

- 服务端单向推送结果
- 前端主要负责实时展示
- 不需要像 WebSocket 那样做双向高频交互

#### 2. 你项目里是怎么接的

你当前项目里的链路比较清楚：

- 后端 `SseController` 暴露 `/sse/connect/{chatSessionId}`
- `SseServiceImpl` 为每个 `chatSessionId` 创建一个 `SseEmitter`
- 服务端用 `ConcurrentHashMap<String, SseEmitter>` 管理会话级连接
- 前端 `AgentChatView` 用 `EventSource` 订阅这个 SSE 地址
- Agent 运行过程中，`JChatMind.refreshPendingMessages()` 会把新消息通过 `sseService.send(...)` 推给前端

也就是说，它不是前端主动一遍遍查“有没有新消息”，而是后端有结果就直接推。

#### 3. 这里的“实时”具体到什么程度

如果你说的是：

- AI 新回复出来后，前端会不会马上显示

那答案是会，这就是实时推送。

但如果你说的是：

- 模型是不是一边生成 token 一边像打字机一样流出来

那当前不是。

你现在实际做的是：

- 后端生成一条完整 `AssistantMessage` 或 `ToolResponseMessage`
- 先持久化
- 再通过 SSE 推给前端

所以它更准确地说是：

- 会话级实时
- 消息级实时
- 非 token 级流式输出

#### 4. 你项目里的现状边界

从当前实现看，前后端类型里虽然预留了：

- `AI_PLANNING`
- `AI_THINKING`
- `AI_EXECUTING`
- `AI_DONE`

但后端当前真正稳定发出去的，主要还是 `AI_GENERATED_CONTENT`。

所以现在实际跑通的是：

- AI 回复实时推送
- 工具结果实时推送

还不是完整意义上的：

- 思考状态全程流式可视化
- token 级逐字输出

#### 5. 面试里最稳的说法

你可以这样讲：

我项目里用 SSE 做会话级的服务端单向推送。前端通过 `EventSource` 订阅指定 `chatSessionId` 的连接，后端在 Agent 运行过程中，一旦产生新的 AI 回复或工具结果，就通过 `SseEmitter` 立即推送给前端展示。它属于消息级实时推送，不是 token 级流式输出，但已经可以满足当前聊天结果的实时展示需求。

### 安全与降级

#### 18. Agent 系统如何防止 prompt injection、工具越权、幻觉输出？

你现在主要做了基础防护：`allowedTools` 和 `allowedKbs` 做白名单隔离，数据库工具只读限制，文件系统工具默认禁用并带路径校验，`MAX_STEPS` 防止循环失控。严格说这还不是完整生产级安全体系，因为还没做专门的 prompt injection 检测和高危工具审批流。

#### 19. 如果模型工具调用失败，你的降级策略是什么？

当前降级比较朴素：很多工具内部会捕获异常并返回错误字符串，作为 `ToolResponseMessage` 回填上下文，让模型还有机会继续决策；如果异常直接冒泡，`run()` 会进入 `ERROR` 并终止。真正的重试、熔断和备用工具链你现在还没完整做。
