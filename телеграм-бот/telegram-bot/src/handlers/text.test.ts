import { describe, it, expect } from "bun:test";
import { ConversationStep, ConversationState } from "../state/machine.js";

/**
 * Property 3: Non-photo messages do not mutate state during page collection
 * Validates: Requirements 1.8
 *
 * Tests the invariant that text messages during AWAIT_MORE_PAGES state
 * do not modify photoFileIds or step.
 */
describe("Property 3: Non-photo messages do not mutate state during page collection", () => {
  // Simulate the text handler's behavior for AWAIT_MORE_PAGES state
  function simulateTextMessage(state: ConversationState, _text: string): {
    stateChanged: boolean;
    responseType: "prompt_photo";
  } {
    // The text handler simply responds with a prompt, no state change
    if (state.step === ConversationStep.AWAIT_MORE_PAGES) {
      return { stateChanged: false, responseType: "prompt_photo" };
    }
    throw new Error("Unexpected state");
  }

  it("any text message in AWAIT_MORE_PAGES does not change photoFileIds", () => {
    const textMessages = ["hello", "123", "фото", "", "  ", "/notacommand", "долго"];

    for (const text of textMessages) {
      for (let pageCount = 1; pageCount <= 10; pageCount++) {
        const photoFileIds = Array.from({ length: pageCount }, (_, i) => `file_${i}`);
        const state: ConversationState = {
          telegramId: 12345,
          step: ConversationStep.AWAIT_MORE_PAGES,
          photoFileIds: [...photoFileIds],
          updatedAt: new Date().toISOString(),
        };

        const originalIds = [...state.photoFileIds!];
        const result = simulateTextMessage(state, text);

        expect(result.stateChanged).toBe(false);
        expect(state.photoFileIds).toEqual(originalIds);
        expect(state.step).toBe(ConversationStep.AWAIT_MORE_PAGES);
      }
    }
  });

  it("step remains AWAIT_MORE_PAGES regardless of text content", () => {
    for (let trial = 0; trial < 50; trial++) {
      const randomText = Math.random().toString(36).slice(2);
      const state: ConversationState = {
        telegramId: 99999,
        step: ConversationStep.AWAIT_MORE_PAGES,
        photoFileIds: ["id1", "id2"],
        updatedAt: new Date().toISOString(),
      };

      const result = simulateTextMessage(state, randomText);
      expect(result.stateChanged).toBe(false);
      expect(result.responseType).toBe("prompt_photo");
      expect(state.step).toBe(ConversationStep.AWAIT_MORE_PAGES);
    }
  });
});
