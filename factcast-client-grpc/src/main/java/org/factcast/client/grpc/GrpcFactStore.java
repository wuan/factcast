package org.factcast.client.grpc;

import static io.grpc.stub.ClientCalls.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import org.factcast.core.Fact;
import org.factcast.core.store.FactStore;
import org.factcast.core.subscription.FactStoreObserver;
import org.factcast.core.subscription.Subscription;
import org.factcast.core.subscription.SubscriptionRequestTO;
import org.factcast.grpc.api.conv.ProtoConverter;
import org.factcast.grpc.api.gen.FactStoreProto;
import org.factcast.grpc.api.gen.FactStoreProto.MSG_Fact;
import org.factcast.grpc.api.gen.FactStoreProto.MSG_Facts;
import org.factcast.grpc.api.gen.FactStoreProto.MSG_Notification;
import org.factcast.grpc.api.gen.FactStoreProto.MSG_OptionalFact;
import org.factcast.grpc.api.gen.FactStoreProto.MSG_SubscriptionRequest;
import org.factcast.grpc.api.gen.RemoteFactStoreGrpc;
import org.factcast.grpc.api.gen.RemoteFactStoreGrpc.RemoteFactStoreBlockingStub;
import org.factcast.grpc.api.gen.RemoteFactStoreGrpc.RemoteFactStoreStub;

import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.stub.StreamObserver;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.springboot.autoconfigure.grpc.client.AddressChannelFactory;

/**
 * Adapter that implements a FactStore by calling a remote one via GRPC.
 * 
 * @author usr
 *
 */
// TODO cleanup

@Slf4j
class GrpcFactStore implements FactStore {

	private static final String CHANNEL_NAME = "factstore";
	private final RemoteFactStoreBlockingStub blockingStub;
	private final RemoteFactStoreStub stub;

	GrpcFactStore(@NonNull AddressChannelFactory channelFactory) {
		Channel c = channelFactory.createChannel(CHANNEL_NAME);
		blockingStub = RemoteFactStoreGrpc.newBlockingStub(c);
		stub = RemoteFactStoreGrpc.newStub(c);
	}

	final ProtoConverter conv = new ProtoConverter();

	@Override
	public Optional<Fact> fetchById(UUID id) {
		log.trace("fetching {} from remote store", id);
		MSG_OptionalFact fetchById = blockingStub.fetchById(conv.toProto(id));
		if (!fetchById.getPresent()) {
			return Optional.empty();
		} else {
			return conv.fromProto(fetchById);
		}
	}

	@Override
	public void publish(@NonNull List<? extends Fact> factsToPublish) {
		log.trace("publishing {} facts to remote store", factsToPublish.size());
		List<MSG_Fact> mf = factsToPublish.stream().map(conv::toProto).collect(Collectors.toList());
		MSG_Facts mfs = MSG_Facts.newBuilder().addAllFact(mf).build();
		blockingStub.publish(mfs);
	}

	@Override
	public CompletableFuture<Subscription> subscribe(@NonNull SubscriptionRequestTO req,
			@NonNull FactStoreObserver observer) {
		CountDownLatch l = new CountDownLatch(1);

		final MSG_SubscriptionRequest request = conv.toProto(req);
		final StreamObserver<FactStoreProto.MSG_Notification> responseObserver = new StreamObserver<FactStoreProto.MSG_Notification>() {

			@Override
			public void onNext(MSG_Notification f) {

				log.trace("observer got msg: {}", f);

				switch (f.getType()) {
				case Catchup:
					l.countDown();
					observer.onCatchup();
					break;
				case Complete:
					l.countDown();
					observer.onComplete();
					break;
				case Error:
					l.countDown();
					observer.onError(new RuntimeException("Server-side Error: \n" + f.getError()));
					break;

				case Fact:
					observer.onNext(conv.fromProto(f.getFact()));
					break;

				case Id:
					// wrap id in a fact
					observer.onNext(new IdOnlyFact(conv.fromProto(f.getId())));
					break;

				case UNRECOGNIZED:
					l.countDown();
					observer.onError(new RuntimeException("Unrecognized notification type. THIS IS A BUG!"));
					break;
				}
			}

			@Override
			public void onError(Throwable t) {
				observer.onError(t);

			}

			@Override
			public void onCompleted() {
				observer.onComplete();
			}
		};

		final ClientCall<MSG_SubscriptionRequest, MSG_Notification> call = stub.getChannel()
				.newCall(RemoteFactStoreGrpc.METHOD_SUBSCRIBE, stub.getCallOptions());
		asyncServerStreamingCall(call, request, responseObserver);

		// wait until catchup
		try {
			l.await();
		} catch (InterruptedException e) {
			throw new IllegalStateException(e);
		}

		return CompletableFuture.completedFuture(() -> {
			call.cancel("Client is no longer interested", null);
		});
	}

	@RequiredArgsConstructor
	final static class IdOnlyFact implements Fact {
		@Getter
		@NonNull
		final UUID id;

		@Override
		public String ns() {
			throw new UnsupportedOperationException();
		}

		@Override
		public String type() {
			throw new UnsupportedOperationException();
		}

		@Override
		public UUID aggId() {
			throw new UnsupportedOperationException();
		}

		@Override
		public String jsonHeader() {
			throw new UnsupportedOperationException();
		}

		@Override
		public String jsonPayload() {
			throw new UnsupportedOperationException();
		}

		@Override
		public String meta(String key) {
			throw new UnsupportedOperationException();
		}

	}
}
