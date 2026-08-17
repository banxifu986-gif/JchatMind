import type { ChatMessageVO, SseMessagePayload, SseMessageType } from "../src/types";

const contentDeltaEvent: SseMessageType = "AI_CONTENT_DELTA";
const contentDeltaPayload: SseMessagePayload = {
  message: {} as ChatMessageVO,
  statusText: "",
  done: false,
  contentDelta: "第一段回答",
};

void contentDeltaEvent;
void contentDeltaPayload;
