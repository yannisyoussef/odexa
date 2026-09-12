package commerce.gateway;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Bounds allocation before accepting bytes and supports cancellation by the controller's response deadline. */
final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final int limit;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;

    LimitedBodySubscriber(int limit) {
        this.limit = limit;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return result;
    }

    @Override
    public synchronized void onSubscribe(Flow.Subscription value) {
        if (subscription != null || result.isDone()) {
            value.cancel();
            return;
        }
        subscription = value;
        value.request(1);
    }

    @Override
    public synchronized void onNext(List<ByteBuffer> buffers) {
        if (result.isDone()) {
            return;
        }
        for (ByteBuffer buffer : buffers) {
            if (buffer.remaining() > limit - output.size()) {
                cancel(new IOException("Upstream response limit exceeded"));
                return;
            }
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            output.writeBytes(bytes);
        }
        subscription.request(1);
    }

    @Override
    public synchronized void onError(Throwable error) {
        result.completeExceptionally(error);
    }

    @Override
    public synchronized void onComplete() {
        if (!result.isDone()) {
            result.complete(output.toByteArray());
        }
    }

    // Deadline cancellation can race with callbacks, including the first onSubscribe.
    synchronized void cancel(Throwable error) {
        result.completeExceptionally(error);
        if (subscription != null) {
            Flow.Subscription current = subscription;
            subscription = null;
            current.cancel();
        }
    }
}
