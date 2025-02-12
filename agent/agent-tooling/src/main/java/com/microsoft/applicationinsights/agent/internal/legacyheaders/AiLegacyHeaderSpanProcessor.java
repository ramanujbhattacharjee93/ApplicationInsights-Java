/*
 * ApplicationInsights-Java
 * Copyright (c) Microsoft Corporation
 * All rights reserved.
 *
 * MIT License
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the ""Software""), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify, merge,
 * publish, distribute, sublicense, and/or sell copies of the Software, and to permit
 * persons to whom the Software is furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED *AS IS*, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
 * PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE
 * FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

package com.microsoft.applicationinsights.agent.internal.legacyheaders;

import com.azure.monitor.opentelemetry.exporter.implementation.SpanDataMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.ImplicitContextKeyed;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import javax.annotation.Nullable;

public class AiLegacyHeaderSpanProcessor implements SpanProcessor {

  @Override
  public void onStart(Context parentContext, ReadWriteSpan span) {
    LegacyIds legacyIds = LegacyIds.fromContext(parentContext);
    // need to check that the parent span is the same as the span context extracted from
    // AiLegacyPropagator, because only want to add these properties to the request span
    if (legacyIds != null
        && legacyIds.spanContext.equals(Span.fromContext(parentContext).getSpanContext())) {
      span.setAttribute(SpanDataMapper.AI_LEGACY_PARENT_ID_KEY, legacyIds.legacyParentId);
      if (legacyIds.legacyRootId != null) {
        span.setAttribute(SpanDataMapper.AI_LEGACY_ROOT_ID_KEY, legacyIds.legacyRootId);
      }
    }
  }

  @Override
  public boolean isStartRequired() {
    return true;
  }

  @Override
  public void onEnd(ReadableSpan span) {}

  @Override
  public boolean isEndRequired() {
    return false;
  }

  public static class LegacyIds implements ImplicitContextKeyed {

    private static final ContextKey<LegacyIds> AI_LEGACY_IDS_KEY =
        ContextKey.named("ai-legacy-ids");

    private final SpanContext spanContext;
    private final String legacyParentId;
    @Nullable private final String legacyRootId;

    private static LegacyIds fromContext(Context context) {
      return context.get(LegacyIds.AI_LEGACY_IDS_KEY);
    }

    public LegacyIds(
        SpanContext spanContext, String legacyParentId, @Nullable String legacyRootId) {
      this.spanContext = spanContext;
      this.legacyParentId = legacyParentId;
      this.legacyRootId = legacyRootId;
    }

    @Override
    public Context storeInContext(Context context) {
      return context.with(AI_LEGACY_IDS_KEY, this);
    }
  }
}
