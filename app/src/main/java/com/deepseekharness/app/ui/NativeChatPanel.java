package com.deepseekharness.app.ui;

import android.app.Activity;
import android.view.Gravity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.core.DiagnosticLog;
import com.deepseekharness.app.util.NativeChatQuestionTrace;
import com.deepseekharness.app.util.NativeChatEventQueue;
import com.deepseekharness.app.util.NativeChatQuestionWait;
import com.deepseekharness.app.util.NativeChatQuestionPayload;
import com.deepseekharness.app.util.NativeChatQuestionTrace.Ref;
import com.deepseekharness.app.util.NativeChatQuestionTrace.Reason;
import static com.deepseekharness.app.util.NativeChatQuestionTrace.Phase.*;
import static com.deepseekharness.app.util.NativeChatQuestionTrace.Reason.*;
import com.deepseekharness.app.util.NativeChatRpc;
import com.deepseekharness.app.util.NativeChatGreeting;
import com.deepseekharness.app.util.NativeChatApproval;
import com.deepseekharness.app.util.NativeChatDetails;
import com.deepseekharness.app.util.NativeChatRetry;
import com.deepseekharness.app.util.NativeChatSessionsRpc;
import com.deepseekharness.app.util.NativeChatRpc.ModelOption;
import com.deepseekharness.app.util.NativeChatRpc.ModelSelection;
import com.google.android.material.slider.Slider;
import com.deepseekharness.app.util.NativeChatStream;
import com.deepseekharness.app.util.NativeChatTranscript;
import com.deepseekharness.app.util.NativeChatRenderPlan;
import com.deepseekharness.app.util.NativeChatRenderQueue;
import com.deepseekharness.app.util.PreviewPageSession.Identity;
import com.deepseekharness.app.util.UiText;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.OkHttpClient;

/** Binds the native composer and timeline to the current authenticated DSH run. */
final class NativeChatPanel {
  private static final OkHttpClient SOCKETS = new OkHttpClient();
  private final ChatHomeActivity host;
  private final HarnessController harness;
  private final SharedPreferences prefs;
  private final NativeChatQuestionTrace questionTrace;
  private String followedSession = "";
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final EditText composer;
  private final ExecutorService historyIo = Executors.newSingleThreadExecutor();
  private final ExecutorService questionIo = Executors.newSingleThreadExecutor();
  private final ExecutorService modelIo = Executors.newSingleThreadExecutor();
  private final ImageButton send;
  private final View addAttachment;
  private final Button modelButton;
  private final View effortControls;
  private final Slider effortSlider;
  private final NativeChatAvatar avatar;
  private ModelOption modelOption;
  private ModelSelection shownModel;
  private long modelRequest;
  private final NativeChatAttachments attachments;
  private boolean pickingModel;
  private final NativeChatSidebar sidebar;
  private final TextView title;
  private List<NativeChatRpc.Session> history = List.of();
  private java.util.Set<String> archivedIds = java.util.Set.of();
  private String historyNotice = UiText.choose("连接后显示历史会话", "Connect to view chats");
  private boolean refreshingHistory;
  private boolean historyRefreshPending;
  private volatile long followGeneration;
  private Runnable snapshotDeadline;
  private final Button newChat;
  private final View topNewChat;
  private final ScrollView scroll;
  private final LinearLayout messages;
  private final TextView partial;
  private final TextView status;
  private final TextView turnStatus;
  private final NativeChatReasoningView liveReasoning;
  private final Button questionButton;
  private androidx.appcompat.app.AlertDialog questionDialog;
  private JsonObject pendingQuestion;
  private String eventClientId = "";
  private String shownQuestion = "";
  private String dialogQuestion = "";
  private volatile LiveQuestion liveQuestion;
  private final NativeChatEventQueue<OwnerEvent> ownerEvents = new NativeChatEventQueue<>();
  private NativeChatQuestionWait questionWait;
  private volatile long questionGeneration;
  private boolean questionWaitReady;
  private Ref dialogTrace;
  private volatile Object answeringRequest;
  private boolean answeringApproval;
  private volatile NativeChatApproval liveApproval;
  private androidx.appcompat.app.AlertDialog approvalDialog;
  private NativeChatDetails details = new NativeChatDetails();
  private boolean displayedCanRetry, retrySuppressed;
  private long displayedInputTokens = -1, displayedOutputTokens = -1;
  private List<Long> displayedSequences = List.of();
  private List<NativeChatDetails.Tool> displayedTools = List.of();
  private List<NativeChatDetails.Attachment> displayedAttachments = List.of();
  private boolean checkingQuestion;
  private boolean questionRefreshPending;

  private record LiveQuestion(
      String clientId,
      String eventId,
      JsonArray questions,
      Ref trace,
      String callId,
      boolean timed) {}

  private record OwnerEvent(LiveQuestion question, NativeChatApproval approval) {}

  private boolean answeringQuestion;
  private String submittedQuestion = "";
  private final Runnable tick = this::renderTurnStatus;
  private boolean stopping;
  private final TextView welcome;
  private NativeChatGreeting.Choice greeting;
  private NativeChatTranscript transcript = new NativeChatTranscript();
  private List<NativeChatTranscript.Message> displayed = List.of();

  private record MessageActionBinding(
      long epoch, Identity identity, NativeChatRpc client, String sessionId) {}

  private MessageActionBinding displayedActionBinding;
  private final java.util.ArrayList<Integer> displayedRowEnds = new java.util.ArrayList<>();
  private final java.util.ArrayList<View> displayedBodies = new java.util.ArrayList<>();
  private final NativeChatRenderQueue renderQueue =
      new NativeChatRenderQueue(
          task -> main.postDelayed(task, 50), main::removeCallbacks, this::renderTranscript);
  private String displayedPartial = "";
  private String displayedLiveReasoning = "";
  private Identity identity;
  private volatile long epoch;
  private volatile NativeChatRpc rpc;
  private NativeChatStream stream;
  private String cookie;
  private String sessionId;
  private boolean loading;
  private boolean sending;
  private boolean reconnectRequired;

  NativeChatPanel(ChatHomeActivity host, EditText composer, Bundle saved) {
    this.host = host;
    this.harness = HarnessController.get(host.getApplicationContext());
    android.content.Context app = host.getApplicationContext();
    questionTrace =
        NativeChatQuestionTrace.asynchronous(line -> DiagnosticLog.record(app, "QUESTION", line));
    this.composer = composer;
    prefs = host.getSharedPreferences(ChatHomeActivity.DRAFT_PREFS, Activity.MODE_PRIVATE);
    sessionId = prefs.getString("native_session", "");
    send = host.findViewById(R.id.native_chat_send);
    addAttachment = host.findViewById(R.id.native_chat_add);
    modelButton = host.findViewById(R.id.native_chat_model);
    effortControls = host.findViewById(R.id.native_chat_effort);
    effortSlider = host.findViewById(R.id.native_chat_effort_slider);
    modelButton.setOnClickListener(view -> loadModelControls(true));
    avatar = new NativeChatAvatar(host);
    newChat = host.findViewById(R.id.native_chat_new);
    topNewChat = host.findViewById(R.id.native_chat_new_top);
    title = host.findViewById(R.id.native_chat_title);
    sidebar =
        new NativeChatSidebar(
            host,
            this::selectConversation,
            this::manageSessions,
            this::manageSchedules,
            this::manageControls,
            this::manageFiles);
    scroll = host.findViewById(R.id.native_chat_scroll);
    NativeChatBottomFollow.keepVisibleOnResize(scroll);
    messages = host.findViewById(R.id.native_chat_messages);
    partial = host.findViewById(R.id.native_chat_partial);
    status = host.findViewById(R.id.native_chat_status);
    turnStatus = host.findViewById(R.id.native_chat_turn_status);
    var timeline = (LinearLayout) partial.getParent();
    liveReasoning = new NativeChatReasoningView(host);
    timeline.addView(liveReasoning, timeline.indexOfChild(partial));
    questionButton = host.findViewById(R.id.native_chat_question);
    questionButton.setText(UiText.choose("回答待处理提问", "Answer pending question"));
    questionButton.setOnClickListener(
        view -> {
          if (liveApproval != null) openApproval();
          else openQuestion();
        });
    welcome = host.findViewById(R.id.native_chat_welcome);
    chooseGreeting(saved);
    attachments = new NativeChatAttachments(host, saved, () -> sessionId, this::statusText);
    send.setOnClickListener(
        v -> {
          if (reconnectRequired && identity != null) {
            Identity active = identity;
            suspend();
            ready(active);
          } else if (transcript.running()) {
            stopTurn();
          } else {
            submit();
          }
        });
    newChat.setOnClickListener(v -> selectConversation(""));
    topNewChat.setOnClickListener(v -> selectConversation(""));
    topNewChat.setContentDescription(UiText.choose("新对话", "New chat"));
    renderControls();
  }

  private void chooseGreeting(Bundle saved) {
    int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
    greeting =
        NativeChatGreeting.restore(
            hour, saved == null ? null : saved.getString("native_greeting_id"));
    if (greeting == null) {
      greeting =
          NativeChatGreeting.choose(
              hour,
              java.util.concurrent.ThreadLocalRandom.current().nextLong(),
              prefs.getString("native_greeting_previous", ""));
      prefs.edit().putString("native_greeting_previous", greeting.id()).apply();
    }
    welcome.setText(UiText.choose(greeting.chinese(), greeting.english()));
  }

  void ready(Identity active) {
    if (active == null) return;
    if (active.equals(identity)) {
      if (rpc != null) host.checkChatAccountSetup(rpc, active);
      return;
    }
    suspend();
    identity = active;
    loading = true;
    long ticket = epoch;
    renderControls();
    io.execute(
        () -> {
          String authenticated = current(ticket, active) ? harness.exchangeDshAuthCookie() : null;
          main.post(
              () -> {
                if (!current(ticket, active)) return;
                if (authenticated == null) {
                  disconnected();
                  return;
                }
                try {
                  cookie = authenticated;
                  rpc = new NativeChatRpc(active.authUrl(), cookie, () -> current(ticket, active));
                  refreshAccountAvatar();
                  host.checkChatAccountSetup(rpc, active);
                  loadModelControls(false);
                  refreshHistory();
                  if (sessionId.isEmpty()) {
                    loading = false;
                    message("", "");
                    renderControls();
                  } else {
                    follow(ticket, active);
                  }
                } catch (RuntimeException invalid) {
                  disconnected();
                }
              });
        });
  }

  private boolean current(long ticket, Identity active) {
    return ticket == epoch
        && active.equals(harness.getReadyWebPageIdentity())
        && !harness.isUserStopped()
        && !harness.isStopping()
        && !harness.hasUnresolvedWebSession();
  }

  private void refreshHistory() {
    if (rpc == null || identity == null) return;
    if (refreshingHistory) {
      historyRefreshPending = true;
      return;
    }
    historyRefreshPending = false;
    NativeChatRpc client = rpc;
    Identity active = identity;
    long ticket = epoch;
    refreshingHistory = true;
    String authCookie = cookie;
    historyIo.execute(
        () -> {
          List<NativeChatRpc.Session> sessions = null;
          java.util.Set<String> archived = null;
          try {
            List<NativeChatRpc.Session> all = client.listSessions();
            archived =
                com.deepseekharness.app.util.NativeChatWorkspaceSnapshot.read(
                    SOCKETS, active.authUrl(), authCookie, () -> current(ticket, active));
            sessions = new java.util.ArrayList<>();
            for (NativeChatRpc.Session session : all) {
              if (!archived.contains(session.id())) sessions.add(session);
            }
          } catch (IOException unavailable) {
            // A list failure must not block the composer or close its live stream.
          }
          List<NativeChatRpc.Session> result = sessions;
          java.util.Set<String> verifiedArchived = archived;
          main.post(
              () -> {
                if (!current(ticket, active) || rpc != client) return;
                refreshingHistory = false;
                history = result == null ? List.of() : result;
                if (verifiedArchived != null) archivedIds = verifiedArchived;
                historyNotice =
                    result == null
                        ? UiText.choose("暂时无法读取历史会话", "Chat history is unavailable")
                        : "";
                renderControls();
                if (historyRefreshPending) refreshHistory();
              });
        });
  }

  private void follow(long ticket, Identity active) {
    follow(ticket, active, REFOLLOW);
  }

  private void follow(long ticket, Identity active, Reason reason) {
    closeStream(reason);
    String target = sessionId;
    followedSession = target;
    questionTrace.mark(STREAM_OPEN, questionTrace.ref(target, "", ""), NONE);
    long generation = followGeneration;
    stream =
        NativeChatStream.open(
            SOCKETS,
            active.authUrl(),
            cookie,
            sessionId,
            () -> current(ticket, active),
            item ->
                main.post(
                    () -> {
                      if (generation != followGeneration
                          || !current(ticket, active)
                          || stream == null) return;
                      try {
                        questionTrace.observe(target, item);
                        boolean wasRunning = transcript.running();
                        boolean wasThinking = transcript.thinking();
                        transcript.apply(item);
                        details.apply(item);
                        if ("snapshot".equals(item.get("type").getAsString())) {
                          clearSnapshotDeadline();
                          refreshQuestion();
                          loading = false;
                          if (!sending) message("", "");
                          renderControls();
                        }
                        showTurnFailure(item);
                        JsonObject event = item.getAsJsonObject("event");
                        if (event != null && event.has("type")) {
                          String kind = event.get("type").getAsString();
                          if ("turn/end".equals(kind)) stopping = false;
                          if ("turn/start".equals(kind)) retrySuppressed = false;
                          if ("turn/end".equals(kind)
                              || "tool/result".equals(kind)
                              || "tool/ptc-dispatch".equals(kind)
                              || "user/message".equals(kind)) refreshQuestion();
                          if ("session/title".equals(kind) || "turn/end".equals(kind))
                            refreshHistory();
                        }
                        renderQueue.request(!"assistant-stream".equals(jsonText(item, "type")));
                        if (wasRunning != transcript.running()
                            || wasThinking != transcript.thinking()) {
                          renderControls();
                        }
                      } catch (RuntimeException invalid) {
                        disconnected();
                      }
                    }),
            frame ->
                main.post(
                    () -> {
                      boolean question = "user-questions/request".equals(jsonText(frame, "event"));
                      Ref received = questionTrace.eventRef(jsonText(frame, "agentId"), frame);
                      if (question) questionTrace.mark(EVENT_RECEIVED, received, LIVE);
                      if (generation == followGeneration
                          && current(ticket, active)
                          && stream != null) {
                        handleQuestionEvent(frame);
                      } else if (question) questionTrace.mark(SUPPRESSED, received, STALE_CALLBACK);
                    }),
            error ->
                main.post(
                    () -> {
                      if (generation == followGeneration && current(ticket, active)) {
                        questionTrace.mark(
                            STREAM_DETACH,
                            questionTrace.ref(target, "", ""),
                            NativeChatQuestionTrace.streamFailure(error));
                        disconnected();
                      }
                    }));
    snapshotDeadline =
        () -> {
          if (generation == followGeneration && current(ticket, active)) {
            questionTrace.mark(STREAM_DETACH, questionTrace.ref(target, "", ""), SNAPSHOT_DEADLINE);
            disconnected();
          }
        };
    main.postDelayed(snapshotDeadline, 12000);
  }

  private void clearSnapshotDeadline() {
    if (snapshotDeadline != null) main.removeCallbacks(snapshotDeadline);
    snapshotDeadline = null;
  }

  private void closeStream(Reason reason) {
    if (stream != null || liveQuestion != null || pendingQuestion != null) {
      questionTrace.mark(STREAM_DETACH, questionTrace.ref(followedSession, "", ""), reason);
    }
    followedSession = "";
    followGeneration++;
    closeQuestionWait();
    ownerEvents.clear();
    dismissQuestion(reason);
    pendingQuestion = null;
    liveQuestion = null;
    liveApproval = null;
    if (approvalDialog != null) approvalDialog.dismiss();
    approvalDialog = null;
    eventClientId = "";
    checkingQuestion = questionRefreshPending = false;
    answeringQuestion = false;
    answeringRequest = null;
    answeringApproval = false;
    submittedQuestion = "";
    renderQuestion();
    stopping = false;
    clearSnapshotDeadline();
    if (stream != null) stream.close();
    stream = null;
  }

  private void showTurnFailure(JsonObject item) {
    JsonObject event = item.getAsJsonObject("event");
    if (event == null || !"turn/end".equals(event.get("type").getAsString())) return;
    JsonObject reason = event.getAsJsonObject("data").getAsJsonObject("reason");
    if (reason != null && "error".equals(reason.get("kind").getAsString())) {
      message("回复失败，请在设置中检查模型和连接", "Reply failed. Check the model and connection in Settings.");
    }
  }

  private void renderQuestion() {
    boolean pending = liveApproval != null || liveQuestion != null || pendingQuestion != null;
    questionButton.setText(
        liveApproval != null
            ? UiText.choose("审批待执行工具", "Review tool approval")
            : UiText.choose("回答待处理提问", "Answer pending question"));
    questionButton.setVisibility(pending ? View.VISIBLE : View.GONE);
    questionButton.setEnabled(!answeringQuestion && !answeringApproval);
  }

  private void handleQuestionEvent(JsonObject frame) {
    String type = jsonText(frame, "type");
    if ("ready".equals(type)) {
      String client = jsonText(frame, "clientId");
      if (client.isBlank() || client.length() > 4096 || sessionId.isBlank()) {
        disconnected();
        return;
      }
      if (!eventClientId.isEmpty()) {
        if (!eventClientId.equals(client)) disconnected();
        return;
      }
      eventClientId = client;
      ownerEvents.reset(client, sessionId);
      return;
    }
    if ("cancel".equals(type)) {
      var removed = ownerEvents.remove(jsonText(frame, "eventId"));
      if (removed == null) return;
      OwnerEvent event = removed.value();
      if (event.question() != null)
        questionTrace.mark(EVENT_CANCEL, event.question().trace(), REMOTE_CANCEL);
      if (event.question() != null && liveQuestion == event.question()) {
        closeQuestionWait();
        dismissQuestion(REMOTE_CANCEL);
        if (answeringRequest == liveQuestion) {
          answeringRequest = null;
          answeringQuestion = false;
        }
        liveQuestion = null;
      }
      if (event.approval() != null && liveApproval == event.approval()) {
        liveApproval = null;
        answeringApproval = false;
        if (approvalDialog != null) approvalDialog.dismiss();
        approvalDialog = null;
      }
      promoteOwnerEvent();
      refreshQuestion();
      return;
    }
    if (!"waterfall".equals(type)) return;
    String eventId = jsonText(frame, "eventId");
    String agentId = jsonText(frame, "agentId");
    Ref ref = questionTrace.eventRef(agentId, frame);
    if (eventClientId.isEmpty()) {
      questionTrace.mark(SUPPRESSED, ref, CLIENT_NOT_READY);
      return;
    }
    NativeChatApproval approval = NativeChatApproval.from(eventClientId, sessionId, frame);
    JsonObject request = jsonObject(frame, "request");
    JsonArray questions = null;
    JsonObject wait = jsonObject(request, "wait");
    boolean timed = jsonBoolean(wait, "timed");
    String callId = jsonText(wait, "callId");
    var rawQuestions = request == null ? null : request.get("questions");
    if ("user-questions/request".equals(jsonText(frame, "event"))
        && sessionId.equals(agentId)
        && rawQuestions != null
        && rawQuestions.isJsonArray()
        && NativeChatQuestionPayload.renderable(rawQuestions.getAsJsonArray())
        && (!timed || !callId.isBlank() && callId.length() <= 4096))
      questions = rawQuestions.getAsJsonArray();
    LiveQuestion question = new LiveQuestion(eventClientId, eventId, questions, ref, callId, timed);
    NativeChatEventQueue.Admission admitted;
    if (approval != null || questions != null) {
      admitted =
          ownerEvents.offer(
              eventClientId,
              agentId,
              eventId,
              new OwnerEvent(questions == null ? null : question, approval));
    } else admitted = ownerEvents.delegate(eventClientId, eventId);
    switch (admitted) {
      case QUEUED -> {
        questionTrace.mark(QUEUED, ref, NONE);
        promoteOwnerEvent();
      }
      case DUPLICATE -> questionTrace.once(SUPPRESSED, ref, DUPLICATE_EVENT);
      case DELEGATE -> {
        questionTrace.mark(
            SUPPRESSED,
            ref,
            !sessionId.equals(agentId)
                ? OTHER_SESSION
                : questions == null && approval == null ? NO_QUESTIONS : QUEUE_FULL);
        finishLiveQuestion(question, null, false);
      }
      case RESET_REQUIRED -> {
        questionTrace.mark(SUPPRESSED, ref, QUEUE_LIMIT);
        disconnected();
      }
      default -> questionTrace.mark(SUPPRESSED, ref, INVALID_EVENT);
    }
  }

  private void promoteOwnerEvent() {
    if (liveQuestion == null && liveApproval == null) {
      var next = ownerEvents.peek();
      if (next != null) {
        liveApproval = next.value().approval();
        liveQuestion = next.value().question();
        if (liveQuestion != null) startQuestionWait(liveQuestion);
      }
    }
    renderQuestion();
    maybeOpenQuestion();
  }

  private void startQuestionWait(LiveQuestion question) {
    closeQuestionWait();
    if (!question.timed()) {
      questionWaitReady = true;
      return;
    }
    NativeChatRpc client = rpc;
    Identity active = identity;
    long ticket = epoch, generation = followGeneration, claim = questionGeneration;
    if (client == null || active == null) {
      disconnected();
      return;
    }
    java.util.function.BooleanSupplier owns =
        () ->
            claim == questionGeneration
                && generation == followGeneration
                && liveQuestion == question
                && rpc == client
                && current(ticket, active);
    questionTrace.mark(CLAIM_OPEN, question.trace(), NONE);
    try {
      questionWait =
          NativeChatQuestionWait.open(
              SOCKETS,
              active.authUrl(),
              cookie,
              sessionId,
              question.callId(),
              owns,
              () ->
                  main.post(
                      () -> {
                        if (!owns.getAsBoolean()) return;
                        questionWaitReady = true;
                        questionTrace.mark(CLAIM_READY, question.trace(), NONE);
                        maybeOpenQuestion();
                      }),
              () ->
                  main.post(
                      () -> {
                        if (!owns.getAsBoolean()) return;
                        finishLiveQuestion(question, null, false, true);
                      }),
              error ->
                  main.post(
                      () -> {
                        if (!owns.getAsBoolean() || answeringRequest == question) return;
                        questionTrace.mark(
                            CLAIM_ENDED,
                            question.trace(),
                            "question-wait-opening-timeout".equals(error)
                                ? CLAIM_OPENING_TIMEOUT
                                : IO_FAILURE);
                        // 传输失败不代表用户决定；断开后网关保留未决事件，等待重连或 Host 期限。
                        disconnected();
                      }));
    } catch (RuntimeException unavailable) {
      questionTrace.mark(CLAIM_ENDED, question.trace(), RUNTIME_FAILURE);
      disconnected();
    }
  }

  private void closeQuestionWait() {
    questionGeneration++;
    if (questionWait != null) questionWait.close();
    questionWait = null;
    questionWaitReady = false;
  }

  private static JsonObject jsonObject(JsonObject object, String key) {
    var value = object == null ? null : object.get(key);
    return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
  }

  private static boolean jsonBoolean(JsonObject object, String key) {
    var value = object == null ? null : object.get(key);
    return value != null
        && value.isJsonPrimitive()
        && value.getAsJsonPrimitive().isBoolean()
        && value.getAsBoolean();
  }

  private void openApproval() {
    NativeChatApproval request = liveApproval;
    if (request == null
        || rpc == null
        || answeringApproval
        || host.isFinishing()
        || approvalDialog != null && approvalDialog.isShowing()
        || questionDialog != null && questionDialog.isShowing()) return;
    String arguments =
        details.tools().stream()
            .filter(tool -> tool.id().equals(request.callId()))
            .map(NativeChatDetails.Tool::arguments)
            .findFirst()
            .orElse("");
    String description =
        request.toolName()
            + "\n"
            + request.reason()
            + (arguments.isBlank() ? "" : "\n\n" + arguments);
    androidx.appcompat.app.AlertDialog shown =
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(host)
            .setTitle(UiText.choose("工具执行审批", "Approve tool execution"))
            .setMessage(description)
            .setPositiveButton(
                UiText.choose("允许一次", "Allow once"), (d, which) -> answerApproval(request, true))
            .setNegativeButton(
                UiText.choose("拒绝", "Reject"), (d, which) -> answerApproval(request, false))
            .create();
    approvalDialog = shown;
    shown.setOnDismissListener(
        d -> {
          if (approvalDialog == shown) approvalDialog = null;
        });
    shown.show();
  }

  private void answerApproval(NativeChatApproval request, boolean allow) {
    if (liveApproval != request || rpc == null || answeringApproval || !request.claim()) return;
    NativeChatRpc client = rpc;
    long generation = followGeneration;
    answeringApproval = true;
    renderQuestion();
    questionIo.execute(
        () -> {
          if (generation != followGeneration || rpc != client || liveApproval != request) return;
          boolean sent = false;
          try {
            client.finishApproval(request, allow);
            sent = true;
          } catch (IOException | RuntimeException unavailable) {
            /* 不重放未确认的审批决定。 */
          }
          boolean confirmed = sent;
          main.post(
              () -> {
                if (generation != followGeneration || rpc != client || liveApproval != request)
                  return;
                answeringApproval = false;
                liveApproval = null;
                ownerEvents.remove(request.eventId());
                if (!confirmed)
                  message(
                      "审批结果未获确认，请等待更新或重连；不会自动重复提交。",
                      "Approval was not confirmed. Wait for an update or reconnect; it will not be resubmitted automatically.");
                promoteOwnerEvent();
              });
        });
  }

  private void manageSessions() {
    if (rpc == null || identity == null || sending || loading) {
      message("连接后可管理会话", "Connect before managing conversations.");
      return;
    }
    NativeChatRpc client = rpc;
    Identity active = identity;
    long ticket = epoch;
    NativeChatSessionsDialog.show(
        host,
        active.authUrl(),
        cookie,
        sessionId,
        () -> current(ticket, active) && rpc == client,
        target -> {
          if (current(ticket, active) && rpc == client) selectConversation(target);
        },
        () -> {
          if (current(ticket, active) && rpc == client) refreshHistory();
        });
  }

  private void manageSchedules() {
    NativeChatScheduleDialog.show(host, () -> {});
  }

  private void manageControls() {
    if (rpc == null || identity == null || sessionId.isBlank()) {
      message("请先连接并打开会话", "Connect and open a conversation first.");
      return;
    }
    Identity active = identity;
    NativeChatRpc client = rpc;
    String target = sessionId;
    long ticket = epoch;
    NativeChatControlsDialog.show(
        host,
        active.authUrl(),
        cookie,
        target,
        () -> current(ticket, active) && rpc == client && target.equals(sessionId));
  }

  private void manageFiles() {
    if (rpc == null || identity == null || sessionId.isBlank()) {
      message("请先连接并打开会话", "Connect and open a conversation first.");
      return;
    }
    Identity active = identity;
    NativeChatRpc client = rpc;
    String target = sessionId;
    long ticket = epoch;
    NativeChatFilesDialog.show(
        host,
        active.authUrl(),
        cookie,
        target,
        () -> current(ticket, active) && rpc == client && target.equals(sessionId));
  }

  private void refreshQuestion() {
    if (rpc == null || sessionId.isEmpty()) return;
    if (checkingQuestion || answeringQuestion) {
      questionRefreshPending = true;
      return;
    }
    checkingQuestion = true;
    questionRefreshPending = false;
    NativeChatRpc client = rpc;
    String target = sessionId;
    long generation = followGeneration;
    questionIo.execute(
        () -> {
          JsonObject batch = null;
          boolean received = false;
          try {
            batch = client.continuedQuestion(target);
            received = true;
          } catch (IOException | RuntimeException unavailable) {
            // A failed projection read leaves the last confirmed question available.
          }
          JsonObject result = batch;
          boolean success = received;
          main.post(
              () -> {
                if (generation != followGeneration || rpc != client) return;
                checkingQuestion = false;
                if (success && !answeringQuestion) {
                  pendingQuestion =
                      result != null
                              && !submittedQuestion.equals(result.get("callId").getAsString())
                          ? result
                          : null;
                  if (pendingQuestion != null)
                    questionTrace.once(
                        PROJECTION,
                        questionTrace.ref(target, jsonText(pendingQuestion, "callId"), ""),
                        NONE);
                  if (liveQuestion == null && !dialogQuestion.equals(questionId()))
                    dismissQuestion(REPLACED);
                  renderQuestion();
                  maybeOpenQuestion();
                }
                if (questionRefreshPending && !answeringQuestion) refreshQuestion();
              });
        });
  }

  private String questionId() {
    if (liveQuestion != null) return "live:" + liveQuestion.eventId();
    return pendingQuestion == null ? "" : "pending:" + pendingQuestion.get("callId").getAsString();
  }

  void maybeOpenQuestion() {
    if (liveApproval != null) {
      if (host.hasWindowFocus() && com.deepseekharness.app.ForegroundActivity.isResumed(host))
        openApproval();
      return;
    }
    String id = questionId();
    if (id.isEmpty()) return;
    if ((sessionId + ":" + id).equals(shownQuestion)) {
      questionTrace.once(SUPPRESSED, currentQuestionRef(), ALREADY_SHOWN);
      return;
    }
    if (host.hasWindowFocus() && com.deepseekharness.app.ForegroundActivity.isResumed(host)) {
      openQuestion();
    } else
      questionTrace.once(
          SUPPRESSED,
          currentQuestionRef(),
          !com.deepseekharness.app.ForegroundActivity.isResumed(host)
              ? HOST_NOT_RESUMED
              : HOST_NO_FOCUS);
  }

  private Ref currentQuestionRef() {
    return liveQuestion != null
        ? liveQuestion.trace()
        : questionTrace.ref(sessionId, jsonText(pendingQuestion, "callId"), "");
  }

  private static String jsonText(JsonObject object, String key) {
    var value = object == null ? null : object.get(key);
    return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
        ? value.getAsString()
        : "";
  }

  private void openQuestion() {
    String id = questionId();
    if (id.isEmpty()) return;
    Ref ref = currentQuestionRef();
    if (answeringQuestion || rpc == null || host.isFinishing()) {
      questionTrace.once(
          SUPPRESSED,
          ref,
          answeringQuestion ? ANSWER_PENDING : rpc == null ? NO_RPC : HOST_FINISHING);
      return;
    }
    if (questionDialog != null && questionDialog.isShowing()) {
      questionTrace.once(SUPPRESSED, ref, DIALOG_OPEN);
      return;
    }
    if (liveQuestion != null && liveQuestion.timed() && !questionWaitReady) {
      questionTrace.once(SUPPRESSED, ref, CLAIM_NOT_READY);
      return;
    }
    LiveQuestion live = liveQuestion;
    JsonObject batch = pendingQuestion;
    try {
      questionDialog =
          NativeChatQuestionDialog.show(
              host,
              live == null ? batch.getAsJsonArray("questions") : live.questions(),
              answers -> {
                if (live == null) submitQuestion(batch, answers);
                else finishLiveQuestion(live, answers, false);
              },
              () -> {
                if (live != null && liveQuestion == live) finishLiveQuestion(live, null, true);
              },
              observation -> {
                questionTrace.dialog(
                    ref,
                    observation.reason(),
                    observation.showing(),
                    observation.attached(),
                    observation.focused());
                if (live == null || liveQuestion != live || questionWait == null) return;
                switch (observation.reason()) {
                  case EDIT -> questionWait.engage();
                  case INPUT_FOCUS -> questionWait.holdFocus();
                  case INPUT_BLUR, DISMISSED, DIALOG_CANCEL -> questionWait.releaseFocus();
                  default -> {}
                }
              });
      dialogTrace = ref;
      dialogQuestion = id;
      shownQuestion = sessionId + ":" + id;
    } catch (RuntimeException invalid) {
      questionTrace.mark(SUPPRESSED, ref, RENDER_FAILURE);
      if (live != null) {
        finishLiveQuestion(live, null, false);
        renderQuestion();
      }
      message("无法读取提问", "Could not read the question.");
    }
  }

  private void dismissQuestion(Reason reason) {
    if (questionDialog != null) {
      questionTrace.mark(DIALOG, dialogTrace == null ? currentQuestionRef() : dialogTrace, reason);
      questionDialog.dismiss();
    }
    questionDialog = null;
    dialogTrace = null;
    dialogQuestion = "";
  }

  private void finishLiveQuestion(LiveQuestion question, JsonArray answers, boolean cancel) {
    finishLiveQuestion(question, answers, cancel, false);
  }

  private void finishLiveQuestion(
      LiveQuestion question, JsonArray answers, boolean cancel, boolean timeout) {
    NativeChatRpc client = rpc;
    long generation = followGeneration;
    if (client == null) {
      questionTrace.mark(SUPPRESSED, question.trace(), NO_RPC);
      return;
    }
    boolean replying = answers != null || cancel || timeout;
    boolean owns = liveQuestion == question;
    if (replying && !owns || owns && answeringQuestion) return;
    if (owns) {
      answeringQuestion = true;
      answeringRequest = question;
      renderQuestion();
    }
    questionTrace.mark(
        timeout ? TIMEOUT : answers != null ? SUBMIT : cancel ? CANCEL : DELEGATE,
        question.trace(),
        timeout ? ASK_TIMED_OUT : answers != null ? ANSWER : cancel ? REJECT : NEXT);
    questionIo.execute(
        () -> {
          if (generation != followGeneration || client != rpc || owns && liveQuestion != question)
            return;
          boolean sent = false;
          try {
            if (timeout) client.timeoutQuestionEvent(question.clientId(), question.eventId());
            else
              client.finishQuestionEvent(question.clientId(), question.eventId(), answers, cancel);
            sent = true;
            questionTrace.mark(
                RPC_CONFIRMED,
                question.trace(),
                timeout ? ASK_TIMED_OUT : answers != null ? ANSWER : cancel ? REJECT : NEXT);
          } catch (IOException | RuntimeException unavailable) {
            questionTrace.mark(
                RPC_FAILED,
                question.trace(),
                unavailable instanceof IOException ? IO_FAILURE : RUNTIME_FAILURE);
          }
          boolean success = sent;
          if (!owns) return;
          main.post(
              () -> {
                if (generation != followGeneration || client != rpc || answeringRequest != question)
                  return;
                answeringQuestion = false;
                answeringRequest = null;
                if (liveQuestion == question) {
                  if (success || !replying || question.timed()) {
                    closeQuestionWait();
                    dismissQuestion(SETTLED);
                    liveQuestion = null;
                    ownerEvents.remove(question.eventId());
                  }
                  if (!success && replying)
                    message(
                        "答案未获确认，请刷新后检查；不会自动重复提交。",
                        "Answer was not confirmed. Refresh to check; it will not be submitted again automatically.");
                }
                promoteOwnerEvent();
                refreshQuestion();
              });
        });
  }

  private void submitQuestion(JsonObject batch, JsonArray answers) {
    if (pendingQuestion == null || answeringQuestion || rpc == null) return;
    String callId = batch.get("callId").getAsString();
    if (!callId.equals(pendingQuestion.get("callId").getAsString())) return;
    NativeChatRpc client = rpc;
    String target = sessionId;
    long generation = followGeneration;
    answeringQuestion = true;
    answeringRequest = batch;
    Ref ref = questionTrace.ref(target, callId, "");
    questionTrace.mark(SUBMIT, ref, ANSWER);
    renderQuestion();
    questionIo.execute(
        () -> {
          if (generation != followGeneration || rpc != client || answeringRequest != batch) return;
          boolean accepted;
          try {
            accepted = client.answerQuestion(target, callId, answers);
            questionTrace.mark(
                accepted ? RPC_CONFIRMED : RPC_FAILED, ref, accepted ? ANSWER : NOT_ACCEPTED);
          } catch (IOException | RuntimeException failure) {
            questionTrace.mark(
                RPC_FAILED, ref, failure instanceof IOException ? IO_FAILURE : RUNTIME_FAILURE);
            accepted = false;
          }
          boolean submitted = accepted;
          main.post(
              () -> {
                if (generation != followGeneration || rpc != client || answeringRequest != batch)
                  return;
                answeringQuestion = false;
                answeringRequest = null;
                if (submitted) {
                  submittedQuestion = callId;
                  pendingQuestion = null;
                } else {
                  message("答案未获确认，请刷新后重试", "Answer was not confirmed. Refresh and try again.");
                }
                renderQuestion();
                refreshQuestion();
              });
        });
  }

  private void stopTurn() {
    if (rpc == null || stopping || !transcript.running()) return;
    NativeChatRpc client = rpc;
    Identity active = identity;
    long ticket = epoch;
    String target = sessionId;
    NativeChatTranscript currentTranscript = transcript;
    questionTrace.mark(TURN_CANCEL, questionTrace.ref(target, "", ""), EXPLICIT_STOP);
    stopping = true;
    renderControls();
    modelIo.execute(
        () -> {
          try {
            if (current(ticket, active)) client.cancel(target);
          } catch (IOException | RuntimeException failure) {
            main.post(
                () -> {
                  if (!current(ticket, active) || transcript != currentTranscript || !stopping)
                    return;
                  stopping = false;
                  message("无法停止生成，请重试", "Could not stop generation. Try again.");
                  renderControls();
                });
          }
        });
  }

  private void renderTurnStatus() {
    main.removeCallbacks(tick);
    boolean running = identity != null && !reconnectRequired && transcript.running();
    long elapsed =
        running && transcript.startedAt() >= 0
            ? Math.max(0, System.currentTimeMillis() - transcript.startedAt())
            : -1;
    String label =
        stopping
            ? UiText.choose("正在停止", "Stopping")
            : transcript.thinking()
                ? UiText.choose("正在思考", "Thinking")
                : UiText.choose("正在处理", "Working");
    turnStatus.setText(elapsed >= 0 ? label + " · " + formatElapsed(elapsed) : label);
    turnStatus.setVisibility(running ? View.VISIBLE : View.GONE);
    if (running) main.postDelayed(tick, 1000);
  }

  private static String formatElapsed(long elapsed) {
    return elapsed < 1000
        ? UiText.choose("少于 1 秒", "< 1 s")
        : UiText.format("%d 秒", elapsed / 1000);
  }

  private void submit() {
    if (rpc == null || loading || sending || transcript.running() || identity == null) return;
    String text = composer.getText().toString();
    String selected = sessionId;
    List<NativeChatAttachments.Draft> included = attachments.draftsFor(selected);
    if (text.isBlank() && included.isEmpty()) return;
    Identity active = identity;
    NativeChatRpc client = rpc;
    long ticket = epoch;
    sending = true;
    message("正在发送", "Sending");
    renderControls();
    if (selected.isEmpty()) {
      io.execute(
          () -> {
            try {
              String created = client.createSession();
              main.post(
                  () -> {
                    if (!current(ticket, active)) return;
                    sessionId = created;
                    SharedPreferences.Editor moved =
                        prefs.edit().putString("native_session", created).remove(draftKey(""));
                    for (String field : List.of("", ":model", ":effort")) {
                      String from = providerKey("") + field;
                      moved
                          .putString(providerKey(created) + field, prefs.getString(from, ""))
                          .remove(from);
                    }
                    moved.apply();
                    attachments.moveNewChatDraft(created);
                    if (modelOption == null) loadModelControls(false);
                    renderControls();
                    try {
                      follow(ticket, active);
                      sendPrompt(client, ticket, active, created, text, included);
                    } catch (RuntimeException unavailable) {
                      sending = false;
                      disconnected();
                    }
                  });
            } catch (IOException | RuntimeException failure) {
              main.post(
                  () -> {
                    if (current(ticket, active)) sendFailed();
                  });
            }
          });
    } else {
      sendPrompt(client, ticket, active, selected, text, included);
    }
  }

  private void sendPrompt(
      NativeChatRpc client,
      long ticket,
      Identity active,
      String target,
      String text,
      List<NativeChatAttachments.Draft> included) {
    // If the response is lost, an explicit retry of the same draft reuses the Host's
    // idempotency key. Neither a reconnect nor process restore auto-submits it.
    String requestKey = "native_pending_request:" + target;
    String textKey = "native_pending_text:" + target;
    String attachmentsKey = "native_pending_attachments:" + target;
    java.util.StringJoiner ids = new java.util.StringJoiner(",");
    for (var attachment : included) ids.add(attachment.id());
    String attachmentIds = ids.toString();
    String previous = prefs.getString(requestKey, "");
    String previousText = prefs.getString(textKey, "");
    SharedPreferences.Editor pending = prefs.edit();
    if (previous.isEmpty() && target.equals(prefs.getString("native_pending_session", ""))) {
      previous = prefs.getString("native_pending_request", "");
      previousText = prefs.getString("native_pending_text", "");
      pending
          .remove("native_pending_request")
          .remove("native_pending_session")
          .remove("native_pending_text");
    }
    String request =
        !previous.isEmpty()
                && text.equals(previousText)
                && attachmentIds.equals(prefs.getString(attachmentsKey, ""))
            ? previous
            : UUID.randomUUID().toString();
    pending
        .putString(requestKey, request)
        .putString(textKey, text)
        .putString(attachmentsKey, attachmentIds)
        .apply();
    String routeKey = providerKey(target);
    ModelSelection selected = pendingModel(target);
    NativeChatStream follower = stream;
    io.execute(
        () -> {
          try {
            client.ensureApiModel(target, selected);
          } catch (IOException | RuntimeException unavailable) {
            main.post(
                () -> {
                  if (!current(ticket, active)) return;
                  sending = false;
                  message(
                      "无法使用 API 模型，请检查 API 配置。草稿已保留。",
                      "Could not use an API model. Check your API settings. Your draft is kept.");
                  renderControls();
                });
            return;
          }
          JsonArray encoded;
          try {
            encoded = attachments.encode(client, target, included);
          } catch (IOException | RuntimeException unavailable) {
            main.post(
                () -> {
                  if (!current(ticket, active)) return;
                  sending = false;
                  message(
                      "附件无法读取或上传，请重选附件。单个文件限 20 MB。",
                      "Could not read or upload the attachment. Pick it again. File limit: 20 MiB.");
                  renderControls();
                });
            return;
          }
          try {
            if (follower != null) follower.awaitQuestionEvents();
            if (!client.prompt(target, request, text, encoded).get("accepted").getAsBoolean()) {
              throw new IOException("DSH prompt was not accepted");
            }
            main.post(
                () -> {
                  if (!current(ticket, active)) return;
                  sending = false;
                  SharedPreferences.Editor saved =
                      prefs.edit().remove(requestKey).remove(textKey).remove(attachmentsKey);
                  if (selected.equals(pendingModel(target))) {
                    saved.remove(routeKey).remove(routeKey + ":model").remove(routeKey + ":effort");
                  }
                  saved.apply();
                  attachments.clearSent(target);
                  if (text.contentEquals(composer.getText())) {
                    composer.setText("");
                    prefs.edit().putString("draft", "").apply();
                  }
                  if (!reconnectRequired
                      && UiText.choose("正在发送", "Sending").contentEquals(status.getText())) {
                    message("", "");
                  }
                  renderControls();
                  refreshHistory();
                });
          } catch (IOException | RuntimeException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            main.post(
                () -> {
                  if (!current(ticket, active)) return;
                  if (failure.getMessage() != null
                      && failure.getMessage().contains("session/attachment-invalid")) {
                    sending = false;
                    message(
                        "当前模型无法接收这个附件，草稿已保留",
                        "This model could not accept the attachment. Your draft is kept.");
                    renderControls();
                  } else {
                    sendFailed();
                  }
                });
          }
        });
  }

  private void sendFailed() {
    sending = false;
    message("发送未确认，草稿已保留", "Send not confirmed. Your draft is kept.");
    renderControls();
  }

  private void disconnected() {
    closeStream(DISCONNECTED);
    rpc = null;
    refreshingHistory = false;
    historyRefreshPending = false;
    loading = false;
    reconnectRequired = true;
    pickingModel = false;
    modelOption = null;
    shownModel = null;
    avatar.clear();
    message("对话连接中断，可重连或到设置检查模型", "Chat disconnected. Reconnect or check model settings.");
    renderControls();
  }

  private void selectConversation(String target) {
    if (sending || loading || identity == null) return;
    host.closeChatOverlay();
    if (target.isEmpty()) chooseGreeting(null);
    if (sessionId.equals(target)) return;
    prefs
        .edit()
        .putString(draftKey(sessionId), composer.getText().toString())
        .putString("native_session", target)
        .apply();
    sessionId = target;
    modelOption = null;
    shownModel = null;
    if (rpc != null) loadModelControls(false);
    composer.setText(prefs.getString(draftKey(target), ""));
    composer.setSelection(composer.length());
    prefs.edit().putString("draft", composer.getText().toString()).apply();
    transcript = new NativeChatTranscript();
    details = new NativeChatDetails();
    retrySuppressed = false;
    renderTranscript();
    if (rpc == null) {
      Identity active = identity;
      suspend();
      ready(active);
    } else if (sessionId.isEmpty()) {
      closeStream(SESSION_SWITCH);
      message("", "");
      renderControls();
    } else {
      loading = true;
      renderControls();
      try {
        follow(epoch, identity, SESSION_SWITCH);
      } catch (RuntimeException unavailable) {
        disconnected();
      }
    }
  }

  private void loadModelControls(boolean openPicker) {
    if (rpc == null || openPicker && (sending || loading || pickingModel)) return;
    String target = sessionId;
    Identity active = identity;
    NativeChatRpc client = rpc;
    long ticket = epoch;
    long request = ++modelRequest;
    pickingModel = openPicker;
    if (openPicker) renderControls();
    modelIo.execute(
        () -> {
          try {
            var catalog = client.modelCatalog();
            ModelSelection session = target.isEmpty() ? null : client.sessionModel(target);
            main.post(
                () -> {
                  if (request != modelRequest || !current(ticket, active) || rpc != client) return;
                  pickingModel = false;
                  renderControls();
                  if (!target.equals(sessionId) || host.isFinishing()) return;
                  ModelSelection effective;
                  try {
                    effective = catalog.resolve(pendingModel(target), session);
                  } catch (IOException unavailable) {
                    showModel(null, null);
                    if (openPicker)
                      NativeChatModelPicker.show(
                          host,
                          catalog,
                          null,
                          choice -> {
                            if (!current(ticket, active)
                                || rpc != client
                                || !target.equals(sessionId)
                                || sending) return;
                            queueModel(choice.selection());
                            showModel(choice, choice.selection());
                          });
                    return;
                  }
                  ModelOption option =
                      catalog.options().stream()
                          .filter(candidate -> candidate.selection().sameModel(effective))
                          .findFirst()
                          .orElse(null);
                  showModel(option, effective);
                  if (!openPicker || sending) return;
                  NativeChatModelPicker.show(
                      host,
                      catalog,
                      effective,
                      choice -> {
                        if (!current(ticket, active)
                            || rpc != client
                            || !target.equals(sessionId)
                            || sending) return;
                        ModelSelection selection =
                            choice.selection().sameModel(shownModel)
                                ? shownModel
                                : choice.selection();
                        queueModel(selection);
                        showModel(choice, selection);
                      });
                });
          } catch (IOException | RuntimeException unavailable) {
            main.post(
                () -> {
                  if (request != modelRequest || !current(ticket, active) || rpc != client) return;
                  pickingModel = false;
                  renderControls();
                  if (openPicker && target.equals(sessionId)) {
                    message("无法读取模型列表，请重试", "Could not load models. Try again.");
                  }
                });
          }
        });
  }

  private void showModel(ModelOption option, ModelSelection selection) {
    modelOption = option;
    shownModel = selection;
    if (option != null && !option.efforts().isEmpty()) {
      NativeChatModelPicker.bindEffort(
          effortControls,
          option,
          selection,
          adjusted -> {
            shownModel = adjusted;
            queueModel(adjusted);
          });
    }
    renderControls();
  }

  void chooseProvider(String provider) {
    if (!NativeChatRpc.isApiProvider(provider)) return;
    showModel(null, null);
    queueModel(new ModelSelection(provider, "", ""));
  }

  void refreshAccountControls() {
    refreshAccountAvatar();
    loadModelControls(false);
  }

  private void refreshAccountAvatar() {
    NativeChatRpc client = rpc;
    if (client != null) avatar.refresh(client);
  }

  private void queueModel(ModelSelection selection) {
    String key = providerKey(sessionId);
    prefs
        .edit()
        .putString(key, selection.provider())
        .putString(key + ":model", selection.model())
        .putString(key + ":effort", selection.effort())
        .apply();
    renderControls();
  }

  private ModelSelection pendingModel(String target) {
    String key = providerKey(target);
    return new ModelSelection(
        prefs.getString(key, ""),
        prefs.getString(key + ":model", ""),
        prefs.getString(key + ":effort", ""));
  }

  void forgetProvider(String provider) {
    var edit = prefs.edit();
    for (var entry : prefs.getAll().entrySet()) {
      String key = entry.getKey();
      if (key.startsWith("native_provider:")
          && !key.endsWith(":model")
          && !key.endsWith(":effort")
          && provider.equals(entry.getValue()))
        edit.remove(key).remove(key + ":model").remove(key + ":effort");
    }
    edit.apply();
    loadModelControls(false);
  }

  void saveComposerState(Bundle saved) {
    if (greeting != null) saved.putString("native_greeting_id", greeting.id());
    attachments.save(saved);
  }

  private static String providerKey(String id) {
    return "native_provider:" + id;
  }

  private static String draftKey(String id) {
    return "native_draft:" + id;
  }

  void dismissSearch() {
    sidebar.dismissSearch();
  }

  void suspend() {
    suspend(SUSPEND);
  }

  private void suspend(Reason reason) {
    renderQueue.cancel();
    epoch++;
    identity = null;
    closeStream(reason);
    rpc = null;
    cookie = null;
    refreshingHistory = false;
    historyRefreshPending = false;
    loading = false;
    sending = false;
    reconnectRequired = false;
    pickingModel = false;
    modelRequest++;
    modelOption = null;
    shownModel = null;
    avatar.clear();
    displayedLiveReasoning = "";
    liveReasoning.showReasoning("");
    message("", "");
    renderControls();
  }

  void destroy() {
    suspend(DESTROY);
    io.shutdownNow();
    historyIo.shutdownNow();
    modelIo.shutdownNow();
    questionIo.shutdownNow();
    avatar.close();
    main.removeCallbacksAndMessages(null);
  }

  private void message(String zh, String en) {
    statusText(UiText.choose(zh, en));
  }

  private void statusText(String text) {
    status.setText(text);
    status.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
  }

  private void renderControls() {
    addAttachment.setEnabled(!sending);
    attachments.render(!sending);
    modelButton.setEnabled(rpc != null && !sending && !loading && !pickingModel);
    String selectedLabel = modelOption == null ? "" : modelOption.name();
    modelButton.setText(
        selectedLabel.isEmpty() ? UiText.choose("选择模型", "Choose model") : selectedLabel);
    modelButton.setContentDescription(
        selectedLabel.isEmpty()
            ? UiText.choose("选择模型", "Choose model")
            : UiText.format("选择模型 · %s", selectedLabel));
    effortControls.setVisibility(
        modelOption != null && !modelOption.efforts().isEmpty() ? View.VISIBLE : View.GONE);
    effortSlider.setEnabled(rpc != null && !sending && !loading && !pickingModel);
    send.setVisibility(identity == null ? View.GONE : View.VISIBLE);
    boolean canStop = !reconnectRequired && transcript.running();
    if (archivedIds.contains(sessionId) && !canStop)
      statusText(
          UiText.choose(
              "此会话已归档，请先在会话管理中恢复。",
              "This chat is archived. Restore it in Manage chats to send messages."));
    send.setContentDescription(
        canStop
            ? UiText.choose("停止生成", "Stop generating")
            : reconnectRequired ? UiText.choose("重连", "Reconnect") : UiText.choose("发送", "Send"));
    send.setImageResource(
        canStop
            ? R.drawable.ic_chat_stop
            : reconnectRequired ? R.drawable.ic_recovery_refresh : R.drawable.ic_chat_send);
    send.setColorFilter(android.graphics.Color.WHITE);
    send.setEnabled(
        !loading
            && (canStop
                ? rpc != null && !stopping
                : !sending
                    && !archivedIds.contains(sessionId)
                    && (rpc != null || reconnectRequired)));
    renderTurnStatus();
    boolean canSwitch = identity != null && !sending && !loading;
    newChat.setEnabled(canSwitch);
    topNewChat.setEnabled(canSwitch);
    String header = UiText.choose("对话", "Chat");
    for (NativeChatRpc.Session session : history) {
      if (session.id().equals(sessionId) && !session.title().isBlank()) {
        header = session.title();
        break;
      }
    }
    title.setText(header);
    sidebar.update(history, sessionId, canSwitch, historyNotice);
  }

  private void addSources(String text) {
    List<NativeChatSources.Source> sources = NativeChatSources.fromReply(text);
    if (sources.isEmpty()) return;
    Button button = new Button(host);
    String title = UiText.choose("来源", "Sources");
    button.setText(title + " (" + sources.size() + ")");
    button.setOnClickListener(
        view ->
            new androidx.appcompat.app.AlertDialog.Builder(host)
                .setTitle(title)
                .setItems(
                    sources.stream()
                        .map(source -> source.title() + "\n" + source.url())
                        .toArray(String[]::new),
                    (dialog, index) -> {
                      try {
                        host.startActivity(
                            new android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(sources.get(index).url()))
                                .addCategory(android.content.Intent.CATEGORY_BROWSABLE));
                      } catch (android.content.ActivityNotFoundException unavailable) {
                        message("无法打开来源链接", "Could not open source link.");
                      }
                    })
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    messages.addView(
        button,
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
  }

  private void renderTranscript() {
    renderQueue.cancel();
    boolean followBottom = !scroll.canScrollVertically(1);
    MessageActionBinding actionBinding = messageActionBinding();
    List<NativeChatTranscript.Message> next = transcript.messages();
    List<Long> nextSequences = transcript.messageSequences();
    List<NativeChatDetails.Tool> nextTools = details.tools();
    List<NativeChatDetails.Attachment> nextAttachments = details.attachments();
    if (!actionBinding.equals(displayedActionBinding)
        || !displayed.equals(next)
        || !displayedTools.equals(nextTools)
        || !displayedAttachments.equals(nextAttachments)
        || displayedInputTokens != details.inputTokens()
        || displayedOutputTokens != details.outputTokens()
        || !displayedSequences.equals(nextSequences)
        || displayedCanRetry != (details.canRetry() && !retrySuppressed)) {
      boolean sameBinding = actionBinding.equals(displayedActionBinding);
      int prefix =
          sameBinding
              ? NativeChatRenderPlan.prefix(displayed, displayedSequences, next, nextSequences)
              : 0;
      int keepViews = prefix == 0 ? 0 : displayedRowEnds.get(prefix - 1);
      var previous = displayed;
      var previousSequences = displayedSequences;
      var previousBodies = new java.util.ArrayList<>(displayedBodies);
      var expanded = new java.util.HashMap<Long, Boolean>();
      if (sameBinding) {
        for (int i = 0; i < messages.getChildCount(); i++) {
          if (messages.getChildAt(i) instanceof NativeChatReasoningView reasoning
              && reasoning.getTag() instanceof Long sequence)
            expanded.put(sequence, reasoning.isExpanded());
        }
      }
      // 页脚（用量、附件）与历史正文分开；新增消息只影响后缀。
      messages.removeViews(keepViews, messages.getChildCount() - keepViews);
      displayedRowEnds.subList(prefix, displayedRowEnds.size()).clear();
      displayedBodies.subList(prefix, displayedBodies.size()).clear();
      for (int messageIndex = prefix; messageIndex < next.size(); messageIndex++) {
        NativeChatTranscript.Message entry = next.get(messageIndex);
        long atSeq = NativeChatRenderPlan.sequence(nextSequences, messageIndex);
        if (entry.assistant()) {
          boolean wasExpanded = expanded.getOrDefault(atSeq, false);
          // 流式入口移交给同一条已保存回复时，保留用户当前的展开选择。
          if (sameBinding
              && messageIndex >= previous.size()
              && liveReasoning.isExpanded()
              && !entry.reasoning().isBlank()
              && entry.reasoning().equals(displayedLiveReasoning)) wasExpanded = true;
          addReplyDetails(entry, atSeq, wasExpanded);
        }
        View line;
        int pad = Math.round(12 * host.getResources().getDisplayMetrics().density);
        if (sameBinding
            && messageIndex < previous.size()
            && atSeq == NativeChatRenderPlan.sequence(previousSequences, messageIndex)
            && entry.assistant() == previous.get(messageIndex).assistant()
            && entry.text().equals(previous.get(messageIndex).text())) {
          line = previousBodies.get(messageIndex);
        } else if (entry.assistant()) {
          line = NativeChatMarkdown.createView(host, entry.text());
        } else {
          TextView text = new TextView(host);
          text.setText(entry.text());
          text.setLineSpacing(host.getResources().getDisplayMetrics().density * 3, 1f);
          text.setTextColor(host.getColor(R.color.gpt_text));
          text.setTextSize(16);
          text.setTextIsSelectable(true);
          text.setPadding(pad, pad, pad, pad);
          line = text;
        }
        LinearLayout.LayoutParams row =
            new LinearLayout.LayoutParams(
                entry.assistant()
                    ? LinearLayout.LayoutParams.MATCH_PARENT
                    : LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        row.gravity = entry.assistant() ? Gravity.START : Gravity.END;
        row.topMargin = pad;
        if (!entry.assistant()) {
          row.setMarginStart(4 * pad);
          line.setBackgroundResource(R.drawable.gpt_chat_composer);
        }
        line.setVisibility(entry.text().isBlank() ? View.GONE : View.VISIBLE);
        messages.addView(line, row);
        if (entry.assistant()) addSources(entry.text());
        addMessageActions(entry, atSeq);
        displayedBodies.add(line);
        displayedRowEnds.add(messages.getChildCount());
      }
      for (int i = prefix; i < previousBodies.size(); i++) {
        View oldBody = previousBodies.get(i);
        if (!displayedBodies.contains(oldBody)) NativeChatRichMarkdown.release(oldBody);
      }
      displayed = next;
      displayedActionBinding = actionBinding;
      displayedTools = nextTools;
      displayedAttachments = nextAttachments;
      displayedInputTokens = details.inputTokens();
      displayedOutputTokens = details.outputTokens();
      displayedSequences = nextSequences;
      displayedCanRetry = details.canRetry() && !retrySuppressed;
      addAttachmentCards(nextAttachments);
      if (displayedCanRetry)
        messages.addView(
            smallAction(UiText.choose("重试失败回复", "Retry failed reply"), this::retryFailedTurn));
      if (details.inputTokens() > 0 || details.outputTokens() > 0) {
        TextView usage = new TextView(host);
        usage.setText(
            UiText.format(
                "已加载记录的 API 用量 · %d in / %d out", details.inputTokens(), details.outputTokens()));
        usage.setTextColor(host.getColor(R.color.gpt_muted));
        usage.setTextSize(12);
        messages.addView(usage);
      }
    }
    String running = transcript.partial();
    if (!displayedPartial.equals(running)) {
      displayedPartial = running;
      partial.setText(NativeChatMarkdown.render(running));
    }
    partial.setVisibility(running.isEmpty() ? View.GONE : View.VISIBLE);
    displayedLiveReasoning = transcript.partialReasoning();
    liveReasoning.showReasoning(displayedLiveReasoning);
    boolean empty =
        next.isEmpty()
            && running.isEmpty()
            && !transcript.running()
            && liveReasoning.getVisibility() != View.VISIBLE;
    welcome.setVisibility(empty ? View.VISIBLE : View.GONE);
    LinearLayout timeline = (LinearLayout) messages.getParent();
    var layout = (android.widget.FrameLayout.LayoutParams) timeline.getLayoutParams();
    int gravity = (empty ? Gravity.CENTER_VERTICAL : Gravity.TOP) | Gravity.CENTER_HORIZONTAL;
    if (layout.gravity != gravity) {
      layout.gravity = gravity;
      timeline.setLayoutParams(layout);
    }
    if (followBottom) NativeChatBottomFollow.afterLayout(scroll);
  }

  private Button smallAction(String label, Runnable click) {
    Button button = new androidx.appcompat.widget.AppCompatButton(host);
    button.setText(label);
    button.setAllCaps(false);
    button.setTextSize(13);
    button.setMinHeight(Math.round(48 * host.getResources().getDisplayMetrics().density));
    button.setTextColor(host.getColor(R.color.gpt_text));
    button.setOnClickListener(v -> click.run());
    return button;
  }

  private MessageActionBinding messageActionBinding() {
    return new MessageActionBinding(epoch, identity, rpc, sessionId);
  }

  private void addMessageActions(NativeChatTranscript.Message entry, long atSeq) {
    MessageActionBinding source = messageActionBinding();
    String sourceCookie = cookie;
    NativeChatMessageActions actions = new NativeChatMessageActions(host, entry.assistant());
    actions.addAction(
        R.drawable.ic_chat_message_copy,
        UiText.choose("复制", "Copy"),
        () -> {
          host.getSystemService(android.content.ClipboardManager.class)
              .setPrimaryClip(
                  android.content.ClipData.newPlainText(
                      "DSHA", entry.text().isBlank() ? entry.reasoning() : entry.text()));
          android.widget.Toast.makeText(
                  host, UiText.choose("已复制", "Copied"), android.widget.Toast.LENGTH_SHORT)
              .show();
        });
    if (atSeq >= 0)
      actions.addAction(
          R.drawable.ic_chat_message_fork,
          UiText.choose("从此分叉", "Fork here"),
          () -> {
            if (source.equals(messageActionBinding())) forkAt(atSeq);
          });
    String messageId = details.messageId(atSeq);
    if (entry.assistant() && !messageId.isBlank())
      actions.addAction(
          R.drawable.ic_chat_message_feedback,
          UiText.choose("反馈", "Feedback"),
          () -> {
            if (!source.equals(messageActionBinding())
                || source.client() == null
                || source.identity() == null
                || source.sessionId().isBlank()) return;
            NativeChatControlsDialog.feedback(
                host,
                source.identity().authUrl(),
                sourceCookie,
                source.sessionId(),
                messageId,
                () ->
                    source.equals(messageActionBinding())
                        && current(source.epoch(), source.identity()));
          });
    messages.addView(actions);
  }

  private void forkAt(long atSeq) {
    if (rpc == null || identity == null || sending || loading) return;
    NativeChatRpc client = rpc;
    String target = sessionId;
    Identity active = identity;
    long ticket = epoch;
    new com.google.android.material.dialog.MaterialAlertDialogBuilder(host)
        .setTitle(UiText.choose("从这条消息分叉？", "Fork from this message?"))
        .setMessage(
            UiText.choose("创建独立会话，原会话保持不变。", "Create an independent chat and keep the original."))
        .setNegativeButton(UiText.choose("取消", "Cancel"), null)
        .setPositiveButton(
            UiText.choose("创建分叉", "Create fork"),
            (d, which) -> {
              if (!current(ticket, active)
                  || rpc != client
                  || !target.equals(sessionId)
                  || sending
                  || loading) return;
              loading = true;
              renderControls();
              historyIo.execute(
                  () -> {
                    String fork = null;
                    try {
                      fork = new NativeChatSessionsRpc(client).fork(target, atSeq);
                    } catch (IOException | RuntimeException unavailable) {
                    }
                    String result = fork;
                    main.post(
                        () -> {
                          if (!current(ticket, active) || rpc != client) return;
                          loading = false;
                          renderControls();
                          refreshHistory();
                          if (result == null)
                            message(
                                "分叉未获确认，请刷新会话列表检查。",
                                "Fork was not confirmed. Refresh the chat list to check.");
                          else selectConversation(result);
                        });
                  });
            })
        .show();
  }

  private void addAttachmentCards(List<NativeChatDetails.Attachment> files) {
    for (NativeChatDetails.Attachment file : files) {
      Button card =
          smallAction(
              (file.image() ? "▧ " : "▤ ")
                  + file.name()
                  + (file.bytes() >= 0 ? " · " + file.bytes() + " B" : ""),
              () -> {
                if (file.image()) previewImage(file);
                else
                  new com.google.android.material.dialog.MaterialAlertDialogBuilder(host)
                      .setTitle(file.name())
                      .setMessage(
                          UiText.choose(
                              "此附件已保存在会话中。请在工作区打开对应文档。",
                              "This attachment is saved with the conversation. Open its document in Workspace."))
                      .setPositiveButton(
                          UiText.choose("浏览当前工作区", "Browse workspace"), (d, which) -> manageFiles())
                      .setNegativeButton(UiText.choose("关闭", "Close"), null)
                      .show();
              });
      messages.addView(card);
    }
  }

  private void previewImage(NativeChatDetails.Attachment file) {
    if (rpc == null || identity == null || sessionId.isBlank()) return;
    NativeChatRpc client = rpc;
    Identity active = identity;
    String target = sessionId;
    long ticket = epoch;
    historyIo.execute(
        () -> {
          android.graphics.Bitmap bitmap = null;
          try {
            JsonObject result = client.imageAttachment(target, file.id());
            byte[] data =
                android.util.Base64.decode(
                    result.get("data").getAsString(), android.util.Base64.DEFAULT);
            var bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
              throw new IOException("Invalid image");
            var options = new android.graphics.BitmapFactory.Options();
            options.inSampleSize = 1;
            while (bounds.outWidth / options.inSampleSize > 2048
                || bounds.outHeight / options.inSampleSize > 2048) options.inSampleSize *= 2;
            bitmap = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, options);
          } catch (IOException | RuntimeException unavailable) {
          }
          android.graphics.Bitmap loaded = bitmap;
          main.post(
              () -> {
                if (!current(ticket, active) || rpc != client || !target.equals(sessionId)) return;
                if (loaded == null) {
                  message("图片无法预览，可在工作区查看。", "Image preview unavailable. Open it in Workspace.");
                  return;
                }
                android.widget.ImageView image = new android.widget.ImageView(host);
                image.setImageBitmap(loaded);
                image.setAdjustViewBounds(true);
                image.setContentDescription(file.name());
                image.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(host)
                    .setTitle(file.name())
                    .setView(image)
                    .setPositiveButton(UiText.choose("关闭", "Close"), null)
                    .show();
              });
        });
  }

  private void retryFailedTurn() {
    if (archivedIds.contains(sessionId)
        || !details.canRetry()
        || retrySuppressed
        || rpc == null
        || identity == null
        || sending
        || loading
        || transcript.running()
        || stream == null) return;
    NativeChatRpc client = rpc;
    Identity active = identity;
    String target = sessionId;
    long ticket = epoch;
    JsonArray original = details.retryContent();
    if (NativeChatRetry.requiresFileUpload(original)) {
      new com.google.android.material.dialog.MaterialAlertDialogBuilder(host)
          .setTitle(UiText.choose("文件需要重新上传", "Upload the file again"))
          .setMessage(
              UiText.choose(
                  "历史文件引用不能直接重发。请重新选择原文件并发送，现有草稿不会被覆盖。",
                  "A saved file reference cannot be resent directly. Select the original file and send it again. Your current draft will not be overwritten."))
          .setPositiveButton(
              UiText.choose("选择附件", "Choose attachment"),
              (d, which) -> {
                if (current(ticket, active)
                    && rpc == client
                    && target.equals(sessionId)
                    && !sending) addAttachment.performClick();
              })
          .setNegativeButton(UiText.choose("取消", "Cancel"), null)
          .show();
      return;
    }
    new com.google.android.material.dialog.MaterialAlertDialogBuilder(host)
        .setTitle(UiText.choose("重试失败回复？", "Retry failed reply?"))
        .setMessage(
            UiText.choose(
                "会重新发送上一条消息及其附件，并可能再次执行工具。当前草稿会保留。",
                "This resends the previous message and attachments and may run tools again. Your current draft is kept."))
        .setNegativeButton(UiText.choose("取消", "Cancel"), null)
        .setPositiveButton(
            UiText.choose("重试", "Retry"),
            (d, which) -> {
              if (!current(ticket, active)
                  || rpc != client
                  || !target.equals(sessionId)
                  || sending
                  || loading
                  || retrySuppressed
                  || !details.canRetry()) return;
              retrySuppressed = true;
              sending = true;
              renderTranscript();
              renderControls();
              String requestId = UUID.randomUUID().toString();
              io.execute(
                  () -> {
                    StringBuilder text = new StringBuilder();
                    for (var part : original) {
                      var block = part.getAsJsonObject();
                      String type = block.get("type").getAsString();
                      if ("text".equals(type)) text.append(block.get("text").getAsString());
                    }
                    boolean accepted = false;
                    try {
                      JsonArray retryAttachments =
                          NativeChatRetry.attachments(
                              original, id -> client.imageAttachment(target, id));
                      client.prompt(target, requestId, text.toString(), retryAttachments);
                      accepted = true;
                    } catch (IOException | RuntimeException unavailable) {
                    }
                    boolean success = accepted;
                    main.post(
                        () -> {
                          if (!current(ticket, active)
                              || rpc != client
                              || !target.equals(sessionId)) return;
                          sending = false;
                          renderControls();
                          if (!success)
                            message(
                                "重试发送未获确认。请检查会话记录，不会自动重复发送。",
                                "Retry was not confirmed. Check the conversation; it will not be sent again automatically.");
                        });
                  });
            })
        .show();
  }

  private void addReplyDetails(
      NativeChatTranscript.Message entry, long sequence, boolean expanded) {
    if (entry.turnMillis() >= 0) {
      TextView time = new TextView(host);
      int pad = Math.round(12 * host.getResources().getDisplayMetrics().density);
      time.setPaddingRelative(pad, pad, pad, 0);
      time.setTextSize(13);
      time.setTextColor(host.getColor(R.color.gpt_muted));
      time.setText(UiText.format("本轮用时 · %s", formatElapsed(entry.turnMillis())));
      messages.addView(time);
    }
    if (!entry.reasoning().isBlank()) {
      var details = new NativeChatReasoningView(host);
      details.showReasoning(entry.reasoning());
      details.setTag(sequence);
      details.setExpanded(expanded);
      messages.addView(details);
    }
  }
}
