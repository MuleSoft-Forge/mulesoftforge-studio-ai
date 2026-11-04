package com.mulesoftforge.studio.ai.llm;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

// A streaming model behind the blocking ChatModel interface the chat loop uses: chat() returns the complete reply,
// so tools only ever run from finished calls, and what arrives meanwhile is reported to the turn's Progress.
final class StreamingChat implements ChatModel {
  private final StreamingChatModel streaming;
  private volatile Progress progress = Progress.NONE;

  StreamingChat(StreamingChatModel streaming) {
    this.streaming = streaming;
  }

  void progress(Progress progress) {
    this.progress = progress == null ? Progress.NONE : progress;
  }

  @Override
  public ChatResponse chat(ChatRequest request) {
    CompletableFuture<ChatResponse> done = new CompletableFuture<>();
    Progress shown = progress;
    streaming.chat(request, new StreamingChatResponseHandler() {
      private String state;
      private int toolIndex = -1;

      @Override
      public void onPartialThinking(PartialThinking thinking) {
        receiving("Thinking…");
      }

      @Override
      public void onPartialResponse(String text) {
        receiving("Writing the answer…");
      }

      @Override
      public void onPartialToolCall(PartialToolCall call) {
        if (call.index() != toolIndex && call.name() != null && !call.name().isBlank()) {
          toolIndex = call.index();
          state = null;
          receiving("Writing a " + call.name() + " call…");
        }
      }

      @Override
      public void onCompleteResponse(ChatResponse response) {
        done.complete(response);
      }

      @Override
      public void onError(Throwable error) {
        done.completeExceptionally(error);
      }

      private void receiving(String what) {
        if (!what.equals(state)) {
          state = what;
          shown.receiving(what);
        }
      }
    });
    try {
      return done.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CancellationException("Interrupted waiting for the model.");
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw new CompletionException(cause);
    }
  }

  @Override
  public ChatRequestParameters defaultRequestParameters() {
    return streaming.defaultRequestParameters();
  }

  // The streaming model calls its own listeners; listing them here as well would record each call twice.
  @Override
  public List<ChatModelListener> listeners() {
    return List.of();
  }

  @Override
  public ModelProvider provider() {
    return streaming.provider();
  }

  @Override
  public Set<Capability> supportedCapabilities() {
    return streaming.supportedCapabilities();
  }
}
