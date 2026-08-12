import { WebSocket } from "k6/websockets";
import { Counter, Trend } from "k6/metrics";

const sent = new Counter("app_messages_sent");
const received = new Counter("app_messages_received");
const errors = new Counter("app_errors");
const latency = new Trend("e2e_latency_ms", true);

const pairs = Number(__ENV.PAIRS || 50);

export const options = {
  scenarios: {
    websocket_load: {
      executor: "per-vu-iterations",
      vus: pairs,
      iterations: 1,
      maxDuration: "5m",
    },
  },
  thresholds: {
    e2e_latency_ms: ["p(95)<200"],
    app_errors: ["count==0"],
  },
};

export default function () {
  const senderId = `sender-${__VU}`;
  const receiverId = `receiver-${__VU}`;

  const sender = new WebSocket(
    `ws://localhost:8081/ws?userId=${senderId}`
  );

  const receiver = new WebSocket(
    `ws://localhost:8083/ws?userId=${receiverId}`
  );

  let senderReady = false;
  let receiverReady = false;
  let intervalId;

  function beginTraffic() {
    if (!senderReady || !receiverReady || intervalId) {
      return;
    }

    // One message per second per pair.
    intervalId = setInterval(() => {
      const sentAt = Date.now();

      sender.send(JSON.stringify({
        type: "chat_message",
        receiverID: receiverId,
        payload: {
          messageId: `${__VU}-${sentAt}`,
          sentAt: sentAt,
          text: "load-test-message",
        },
      }));

      sent.add(1);
    }, 1000);

    // Generate traffic for 300 seconds.
    setTimeout(() => {
      clearInterval(intervalId);
    }, 100000);

    // Allow five seconds for in-flight messages, then disconnect.
    setTimeout(() => {
      sender.close();
      receiver.close();
    }, 105000);
  }

  sender.addEventListener("open", () => {
    senderReady = true;
    beginTraffic();
  });

  receiver.addEventListener("open", () => {
    receiverReady = true;
    beginTraffic();
  });

  receiver.addEventListener("message", event => {
    try {
      const message = JSON.parse(event.data);

      if (message.type === "chat_message" && message.payload?.sentAt) {
        received.add(1);
        latency.add(Date.now() - message.payload.sentAt);
      }
    } catch {
      errors.add(1);
    }
  });

  sender.addEventListener("error", () => errors.add(1));
  receiver.addEventListener("error", () => errors.add(1));
}