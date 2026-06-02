#!/bin/bash
set -e

MODEL="bge-m3"

# 后台启动 Ollama 服务
ollama serve &
OLLAMA_PID=$!

# 等待 Ollama 就绪
echo "等待 Ollama 服务启动..."
until ollama list > /dev/null 2>&1; do
  sleep 2
done
echo "Ollama 服务已就绪"

# 检查模型是否已存在，不存在则拉取
if ollama list | grep -q "$MODEL"; then
  echo "模型 $MODEL 已存在，跳过拉取"
else
  echo "正在拉取模型 $MODEL ..."
  ollama pull "$MODEL"
  echo "模型 $MODEL 拉取完成"
fi

# 保持 Ollama 前台运行
echo "Ollama 运行中，端口 11434"
wait $OLLAMA_PID
