package org.folio.inventory.eventhandlers;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.folio.DataImportEventPayload;
import org.folio.MatchDetail;
import org.folio.MatchProfile;
import org.folio.inventory.common.Context;
import org.folio.inventory.common.api.request.PagingParameters;
import org.folio.inventory.common.domain.Failure;
import org.folio.inventory.common.domain.MultipleRecords;
import org.folio.inventory.common.domain.Success;
import org.folio.inventory.dataimport.handlers.matching.MatchInstanceEventHandler;
import org.folio.inventory.dataimport.handlers.matching.loaders.InstanceLoader;
import org.folio.inventory.domain.instances.Instance;
import org.folio.inventory.domain.instances.InstanceCollection;
import org.folio.inventory.storage.Storage;
import org.folio.processing.events.services.handler.EventHandler;
import org.folio.processing.matching.loader.MatchValueLoaderFactory;
import org.folio.processing.matching.reader.MarcValueReaderImpl;
import org.folio.processing.matching.reader.MatchValueReaderFactory;
import org.folio.processing.value.MissingValue;
import org.folio.processing.value.StringValue;
import org.folio.rest.jaxrs.model.EntityType;
import org.folio.rest.jaxrs.model.Field;
import org.folio.rest.jaxrs.model.MatchExpression;
import org.folio.rest.jaxrs.model.ProfileSnapshotWrapper;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import java.util.function.Consumer;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.folio.DataImportEventTypes.DI_INVENTORY_INSTANCE_MATCHED;
import static org.folio.DataImportEventTypes.DI_INVENTORY_INSTANCE_NOT_MATCHED;
import static org.folio.DataImportEventTypes.DI_SRS_MARC_BIB_RECORD_CREATED;
import static org.folio.MatchDetail.MatchCriterion.EXACTLY_MATCHES;
import static org.folio.rest.jaxrs.model.EntityType.INSTANCE;
import static org.folio.rest.jaxrs.model.EntityType.MARC_BIBLIOGRAPHIC;
import static org.folio.rest.jaxrs.model.MatchExpression.DataValueType.VALUE_FROM_RECORD;
import static org.folio.rest.jaxrs.model.ProfileSnapshotWrapper.ContentType.MAPPING_PROFILE;
import static org.folio.rest.jaxrs.model.ProfileSnapshotWrapper.ContentType.MATCH_PROFILE;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@RunWith(VertxUnitRunner.class)
public class MatchInstanceEventHandlerUnitTest {

  private static final String INSTANCE_HRID = "in0001234";
  private static final String INSTANCE_ID = "ddd266ef-07ac-4117-be13-d418b8cd6902";

  @Mock
  private Storage storage;
  @Mock
  private InstanceCollection instanceCollection;
  @Mock
  private MarcValueReaderImpl marcValueReader;
  @InjectMocks
  private InstanceLoader instanceLoader = new InstanceLoader(storage, Vertx.vertx());

  @Before
  public void setUp() {
    MatchValueReaderFactory.clearReaderFactory();
    MatchValueLoaderFactory.clearLoaderFactory();
    MockitoAnnotations.initMocks(this);
    when(marcValueReader.isEligibleForEntityType(MARC_BIBLIOGRAPHIC)).thenReturn(true);
    when(storage.getInstanceCollection(any(Context.class))).thenReturn(instanceCollection);
    when(marcValueReader.read(any(DataImportEventPayload.class), any(MatchDetail.class)))
      .thenReturn(StringValue.of(INSTANCE_HRID));
    MatchValueReaderFactory.register(marcValueReader);
    MatchValueLoaderFactory.register(instanceLoader);
  }

  @Test
  public void shouldMatchOnHandleEventPayload(TestContext testContext) throws UnsupportedEncodingException {
    Async async = testContext.async();

    doAnswer(ans -> {
      Consumer<Success<MultipleRecords<Instance>>> callback =
        (Consumer<Success<MultipleRecords<Instance>>>) ans.getArguments()[2];
      Success<MultipleRecords<Instance>> result =
        new Success<>(new MultipleRecords<>(singletonList(createInstance()), 1));
      callback.accept(result);
      return null;
    }).when(instanceCollection)
      .findByCql(eq(format("hrid == \"%s\"", INSTANCE_HRID)), any(PagingParameters.class), any(Consumer.class), any(Consumer.class));

    EventHandler eventHandler = new MatchInstanceEventHandler();
    DataImportEventPayload eventPayload = createEventPayload();

    eventHandler.handle(eventPayload).whenComplete((updatedEventPayload, throwable) -> {
      testContext.assertNull(throwable);
      testContext.assertEquals(1, updatedEventPayload.getEventsChain().size());
      testContext.assertEquals(
        updatedEventPayload.getEventsChain(),
        singletonList(DI_SRS_MARC_BIB_RECORD_CREATED.value())
      );
      testContext.assertEquals(DI_INVENTORY_INSTANCE_MATCHED.value(), updatedEventPayload.getEventType());
      async.complete();
    });
  }

  @Test
  public void shouldNotMatchOnHandleEventPayload(TestContext testContext) throws UnsupportedEncodingException {
    Async async = testContext.async();

    doAnswer(ans -> {
      Consumer<Success<MultipleRecords<Instance>>> callback =
        (Consumer<Success<MultipleRecords<Instance>>>) ans.getArguments()[2];
      Success<MultipleRecords<Instance>> result =
        new Success<>(new MultipleRecords<>(new ArrayList<>(), 0));
      callback.accept(result);
      return null;
    }).when(instanceCollection)
      .findByCql(anyString(), any(PagingParameters.class), any(Consumer.class), any(Consumer.class));

    EventHandler eventHandler = new MatchInstanceEventHandler();
    DataImportEventPayload eventPayload = createEventPayload();

    eventHandler.handle(eventPayload).whenComplete((updatedEventPayload, throwable) -> {
      testContext.assertNull(throwable);
      testContext.assertEquals(1, updatedEventPayload.getEventsChain().size());
      testContext.assertEquals(
        updatedEventPayload.getEventsChain(),
        singletonList(DI_SRS_MARC_BIB_RECORD_CREATED.value())
      );
      testContext.assertEquals(DI_INVENTORY_INSTANCE_NOT_MATCHED.value(), updatedEventPayload.getEventType());
      async.complete();
    });
  }

  @Test
  public void shouldFailOnHandleEventPayloadIfMatchedMultipleInstances(TestContext testContext) throws UnsupportedEncodingException {
    Async async = testContext.async();

    doAnswer(ans -> {
      Consumer<Success<MultipleRecords<Instance>>> callback =
        (Consumer<Success<MultipleRecords<Instance>>>) ans.getArguments()[2];
      Success<MultipleRecords<Instance>> result =
        new Success<>(new MultipleRecords<>(asList(createInstance(), createInstance()), 2));
      callback.accept(result);
      return null;
    }).when(instanceCollection)
      .findByCql(anyString(), any(PagingParameters.class), any(Consumer.class), any(Consumer.class));

    EventHandler eventHandler = new MatchInstanceEventHandler();
    DataImportEventPayload eventPayload = createEventPayload();

    eventHandler.handle(eventPayload).whenComplete((updatedEventPayload, throwable) -> {
      testContext.assertNotNull(throwable);
      async.complete();
    });
  }

  @Test
  public void shouldFailOnHandleEventPayloadIfFailedCallToInventoryStorage(TestContext testContext) throws UnsupportedEncodingException {
    Async async = testContext.async();

    doAnswer(ans -> {
      Consumer<Failure> callback =
        (Consumer<Failure>) ans.getArguments()[3];
      Failure result =
        new Failure("Internal Server Error", 500);
      callback.accept(result);
      return null;
    }).when(instanceCollection)
      .findByCql(anyString(), any(PagingParameters.class), any(Consumer.class), any(Consumer.class));

    EventHandler eventHandler = new MatchInstanceEventHandler();
    DataImportEventPayload eventPayload = createEventPayload();

    eventHandler.handle(eventPayload).whenComplete((updatedEventPayload, throwable) -> {
      testContext.assertNotNull(throwable);
      async.complete();
    });
  }

  @Test
  public void shouldFailOnHandleEventPayloadIfExceptionThrown(TestContext testContext) throws UnsupportedEncodingException {
    Async async = testContext.async();

    doThrow(new UnsupportedEncodingException()).when(instanceCollection)
      .findByCql(anyString(), any(PagingParameters.class), any(Consumer.class), any(Consumer.class));

    EventHandler eventHandler = new MatchInstanceEventHandler();
    DataImportEventPayload eventPayload = createEventPayload();

    eventHandler.handle(eventPayload).whenComplete((updatedEventPayload, throwable) -> {
      testContext.assertNotNull(throwable);
      async.complete();
    });
  }

  @Test
  public void shouldNotMatchOnHandleEventPayloadIfValueIsMissing(TestContext testContext) {
    Async async = testContext.async();

    when(marcValueReader.read(any(DataImportEventPayload.class), any(MatchDetail.class)))
      .thenReturn(MissingValue.getInstance());

    EventHandler eventHandler = new MatchInstanceEventHandler();
    DataImportEventPayload eventPayload = createEventPayload();

    eventHandler.handle(eventPayload).whenComplete((updatedEventPayload, throwable) -> {
      testContext.assertNull(throwable);
      testContext.assertEquals(1, updatedEventPayload.getEventsChain().size());
      testContext.assertEquals(
        updatedEventPayload.getEventsChain(),
        singletonList(DI_SRS_MARC_BIB_RECORD_CREATED.value())
      );
      testContext.assertEquals(DI_INVENTORY_INSTANCE_NOT_MATCHED.value(), updatedEventPayload.getEventType());
      async.complete();
    });
  }

  @Test
  public void shouldReturnFalseOnIsEligibleIfNullCurrentNode() {
    EventHandler eventHandler = new MatchInstanceEventHandler();
    DataImportEventPayload eventPayload = new DataImportEventPayload();
    assertFalse(eventHandler.isEligible(eventPayload));
  }

  @Test
  public void shouldReturnFalseOnIsEligibleIfCurrentNodeTypeIsNotMatchProfile() {
    EventHandler eventHandler = new MatchInstanceEventHandler();
    DataImportEventPayload eventPayload = new DataImportEventPayload()
      .withCurrentNode(new ProfileSnapshotWrapper()
        .withContentType(MAPPING_PROFILE));
    assertFalse(eventHandler.isEligible(eventPayload));
  }

  @Test
  public void shouldReturnFalseOnIsEligibleForNotInstanceMatchProfile() {
    EventHandler eventHandler = new MatchInstanceEventHandler();
    DataImportEventPayload eventPayload = new DataImportEventPayload()
      .withCurrentNode(new ProfileSnapshotWrapper()
        .withContentType(MATCH_PROFILE)
        .withContent(JsonObject.mapFrom(new MatchProfile()
          .withExistingRecordType(MARC_BIBLIOGRAPHIC))));
    assertFalse(eventHandler.isEligible(eventPayload));
  }

  @Test
  public void shouldReturnTrueOnIsEligibleForInstanceMatchProfile() {
    EventHandler eventHandler = new MatchInstanceEventHandler();
    DataImportEventPayload eventPayload = new DataImportEventPayload()
      .withCurrentNode(new ProfileSnapshotWrapper()
        .withContentType(MATCH_PROFILE)
        .withContent(JsonObject.mapFrom(new MatchProfile()
          .withExistingRecordType(INSTANCE))));
    assertTrue(eventHandler.isEligible(eventPayload));
  }

  @Test
  public void shouldMatchWithSubMatchByInstanceOnHandleEventPayload(TestContext testContext) throws UnsupportedEncodingException {
    Async async = testContext.async();

    doAnswer(ans -> {
      Consumer<Success<MultipleRecords<Instance>>> callback =
        (Consumer<Success<MultipleRecords<Instance>>>) ans.getArguments()[2];
      Success<MultipleRecords<Instance>> result =
        new Success<>(new MultipleRecords<>(singletonList(createInstance()), 1));
      callback.accept(result);
      return null;
    }).when(instanceCollection)
      .findByCql(eq(format("hrid == \"%s\" AND id == \"%s\"", INSTANCE_HRID, INSTANCE_ID)),
        any(PagingParameters.class), any(Consumer.class), any(Consumer.class));

    EventHandler eventHandler = new MatchInstanceEventHandler();
    HashMap<String, String> context = new HashMap<>();
    context.put(EntityType.INSTANCE.value(), JsonObject.mapFrom(createInstance()).encode());
    DataImportEventPayload eventPayload = createEventPayload().withContext(context);

    eventHandler.handle(eventPayload).whenComplete((updatedEventPayload, throwable) -> {
      testContext.assertNull(throwable);
      testContext.assertEquals(1, updatedEventPayload.getEventsChain().size());
      testContext.assertEquals(
        updatedEventPayload.getEventsChain(),
        singletonList(DI_SRS_MARC_BIB_RECORD_CREATED.value())
      );
      testContext.assertEquals(DI_INVENTORY_INSTANCE_MATCHED.value(), updatedEventPayload.getEventType());
      async.complete();
    });
  }

  private DataImportEventPayload createEventPayload() {
    return new DataImportEventPayload()
      .withEventType(DI_SRS_MARC_BIB_RECORD_CREATED.value())
      .withEventsChain(new ArrayList<>())
      .withOkapiUrl("http://localhost:9493")
      .withTenant("diku")
      .withToken("token")
      .withContext(new HashMap<>())
      .withCurrentNode(new ProfileSnapshotWrapper()
        .withId(UUID.randomUUID().toString())
        .withContentType(MATCH_PROFILE)
        .withContent(new MatchProfile()
          .withExistingRecordType(INSTANCE)
          .withIncomingRecordType(MARC_BIBLIOGRAPHIC)
          .withMatchDetails(singletonList(new MatchDetail()
            .withMatchCriterion(EXACTLY_MATCHES)
            .withExistingMatchExpression(new MatchExpression()
              .withDataValueType(VALUE_FROM_RECORD)
              .withFields(singletonList(
                new Field().withLabel("field").withValue("instance.hrid"))
              ))))));
  }

  private Instance createInstance() {
    return new Instance(INSTANCE_ID, INSTANCE_HRID, "MARC", "Wonderful", "12334");
  }

}
