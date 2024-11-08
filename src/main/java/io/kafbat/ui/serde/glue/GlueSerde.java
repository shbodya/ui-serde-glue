package io.kafbat.ui.serde.glue;

import com.amazonaws.services.schemaregistry.common.AWSDeserializerInput;
import com.amazonaws.services.schemaregistry.common.GlueSchemaRegistryDataFormatDeserializer;
import com.amazonaws.services.schemaregistry.common.configs.GlueSchemaRegistryConfiguration;
import com.amazonaws.services.schemaregistry.deserializers.GlueSchemaRegistryDeserializationFacade;
import com.amazonaws.services.schemaregistry.deserializers.GlueSchemaRegistryDeserializerDataParser;
import com.amazonaws.services.schemaregistry.deserializers.GlueSchemaRegistryDeserializerFactory;
import com.amazonaws.services.schemaregistry.serializers.GlueSchemaRegistrySerializationFacade;
import com.amazonaws.services.schemaregistry.serializers.json.JsonDataWithSchema;
import com.amazonaws.services.schemaregistry.utils.AvroRecordType;
import com.amazonaws.services.schemaregistry.utils.ProtobufMessageType;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.protobuf.DynamicMessage;
import io.confluent.kafka.schemaregistry.protobuf.ProtobufSchema;
import io.kafbat.ui.serde.api.DeserializeResult;
import io.kafbat.ui.serde.api.PropertyResolver;
import io.kafbat.ui.serde.api.RecordHeaders;
import io.kafbat.ui.serde.api.SchemaDescription;
import io.kafbat.ui.serde.api.Serde;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.validation.constraints.NotNull;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.profiles.ProfileFileSystemSetting;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.DataFormat;
import software.amazon.awssdk.services.glue.model.EntityNotFoundException;
import software.amazon.awssdk.services.glue.model.GetSchemaRequest;
import software.amazon.awssdk.services.glue.model.GetSchemaResponse;
import software.amazon.awssdk.services.glue.model.GetSchemaVersionRequest;
import software.amazon.awssdk.services.glue.model.GetSchemaVersionResponse;
import software.amazon.awssdk.services.glue.model.SchemaId;
import software.amazon.awssdk.services.glue.model.SchemaVersionNumber;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;

public class GlueSerde implements Serde {

  private final LoadingCache<String, Boolean> schemaExistenceCache = CacheBuilder.newBuilder()
      .maximumSize(1000)
      .expireAfterWrite(5, TimeUnit.MINUTES)
      .build(CacheLoader.from(this::schemaExists));

  private GlueClient glueClient;
  private GlueSchemaRegistryDeserializationFacade deserializationFacade;
  private GlueSchemaRegistrySerializationFacade serializationFacade;

  private String registryName;

  @Nullable
  private String keySchemaNameTemplate;
  private String valueSchemaNameTemplate;

  // schema name -> topics patterns
  private List<Map.Entry<String, Pattern>> topicKeysSchemas;
  private List<Map.Entry<String, Pattern>> topicValuesSchemas;

  private boolean checkSchemaExistenceForDeserialize;

  @Override
  public void configure(PropertyResolver serdeProperties,
                        PropertyResolver clusterProperties,
                        PropertyResolver appProperties) {
    configure(
        createCredentialsProvider(serdeProperties),
        serdeProperties.getProperty("region", String.class)
            .orElseThrow(() -> new IllegalArgumentException("region not provided for GlueSerde")),
        serdeProperties.getProperty("endpoint", String.class).orElse(null),
        serdeProperties.getProperty("roleArn", String.class).orElse(null),
        serdeProperties.getProperty("registry", String.class)
            .orElseThrow(() -> new IllegalArgumentException("registry not provided for GlueSerde")),
        serdeProperties.getProperty("keySchemaNameTemplate", String.class)
            .orElse(null), // there is no default for that
        serdeProperties.getProperty("valueSchemaNameTemplate", String.class)
            .orElse("%s"), // by default Serializer supposes that schemaName == topic name
        serdeProperties.getMapProperty("topicKeysSchemas", String.class, String.class)
            .orElse(Map.of())
            .entrySet()
            .stream()
            .map(e -> Map.entry(e.getKey(), Pattern.compile(e.getValue())))
            .collect(Collectors.toUnmodifiableList()),
        serdeProperties.getMapProperty("topicValuesSchemas", String.class, String.class)
            .orElse(Map.of())
            .entrySet()
            .stream()
            .map(e -> Map.entry(e.getKey(), Pattern.compile(e.getValue())))
            .collect(Collectors.toUnmodifiableList()),
        serdeProperties.getProperty("checkSchemaExistenceForDeserialize", Boolean.class)
            .orElse(false)
    );
  }

  void configure(AwsCredentialsProvider credentialsProvider,
                 String region,
                 @Nullable String endpoint,
                 @Nullable String roleArn,
                 String registryName,
                 @Nullable String keySchemaNameTemplate,
                 String valueSchemaNameTemplate,
                 List<Map.Entry<String, Pattern>> topicKeysSchemas,
                 List<Map.Entry<String, Pattern>> topicValuesSchemas,
                 boolean checkSchemaExistenceForDeserialize
  ) {
    this.glueClient = GlueClient.builder()
        .region(Region.of(region))
        .endpointOverride(Optional.ofNullable(endpoint).map(URI::create).orElse(null))
        .credentialsProvider(credentialsProvider)
        .httpClient(ApacheHttpClient.create())
        .build();
    GlueSchemaRegistryConfiguration sRegConfiguration = glueSrConfig(region, endpoint);
    this.deserializationFacade = createDeserializationFacade(sRegConfiguration, credentialsProvider);
    this.serializationFacade = GlueSchemaRegistrySerializationFacade.builder()
        .glueSchemaRegistryConfiguration(sRegConfiguration)
        .credentialProvider(credentialsProvider)
        .build();
    this.registryName = registryName;
    this.keySchemaNameTemplate = keySchemaNameTemplate;
    this.valueSchemaNameTemplate = valueSchemaNameTemplate;
    this.topicKeysSchemas = topicKeysSchemas;
    this.topicValuesSchemas = topicValuesSchemas;
    this.checkSchemaExistenceForDeserialize = checkSchemaExistenceForDeserialize;
  }

  private GlueSchemaRegistryDeserializationFacade createDeserializationFacade(
      GlueSchemaRegistryConfiguration sRegConfiguration,
      AwsCredentialsProvider credentialsProvider) {
    var facade = new GlueSchemaRegistryDeserializationFacade(sRegConfiguration, credentialsProvider);
    facade.setDeserializerFactory(new FixedDeserializerFactory());
    return facade;
  }

  @VisibleForTesting
  static AwsCredentialsProvider createCredentialsProvider(PropertyResolver serdeProperties) {
    Optional<String> awsAccessKey = serdeProperties.getProperty("awsAccessKeyId", String.class);
    Optional<String> awsSecretKey = serdeProperties.getProperty("awsSecretAccessKey", String.class);
    Optional<String> awsSessionToken = serdeProperties.getProperty("awsSessionToken", String.class);
    if (awsAccessKey.isPresent() && awsSecretKey.isPresent()) {
      return awsSessionToken.<AwsCredentialsProvider>map(
              s -> () -> AwsSessionCredentials.create(awsAccessKey.get(), awsSecretKey.get(), s))
          .orElseGet(() -> () -> AwsBasicCredentials.create(awsAccessKey.get(), awsSecretKey.get()));
    }

    Optional<String> roleArn = serdeProperties.getProperty("roleArn", String.class);
    if (roleArn.isPresent()) {
      return StsAssumeRoleCredentialsProvider.builder()
          .refreshRequest(b -> b.roleArn(roleArn.get())
              .roleSessionName("kafbat-ui-" + UUID.randomUUID()))
          .stsClient(StsClient.builder()
              .credentialsProvider(DefaultCredentialsProvider.create())
              .region(Region.of(serdeProperties.getProperty("region", String.class)
                  .orElseThrow(() -> new IllegalArgumentException("region required for assume role"))))
              .build())
          .build();
    }

    Optional<String> profileName = serdeProperties.getProperty("awsProfileName", String.class);
    Optional<String> profileFile = serdeProperties.getProperty("awsProfileFile", String.class);
    if (profileName.isPresent() || profileFile.isPresent()) {
      ProfileFile file = profileFile.map(filePath ->
              ProfileFile.builder()
                  .type(ProfileFile.Type.CREDENTIALS)
                  .content(Path.of(filePath))
                  .build()
          )
          .orElse(ProfileFile.defaultProfileFile());
      return ProfileCredentialsProvider.builder()
          .profileName(profileName.orElse(ProfileFileSystemSetting.AWS_PROFILE.defaultValue()))
          .profileFile(file)
          .build();
    }

    // if creds properties weren't specified explicitly - using default creds provider
    return DefaultCredentialsProvider.create();
  }

  private static GlueSchemaRegistryConfiguration glueSrConfig(String region, @Nullable String endpoint) {
    GlueSchemaRegistryConfiguration config = new GlueSchemaRegistryConfiguration(region);
    config.setProtobufMessageType(ProtobufMessageType.DYNAMIC_MESSAGE);
    config.setAvroRecordType(AvroRecordType.GENERIC_RECORD);
    config.setSchemaAutoRegistrationEnabled(false);
    config.setEndPoint(endpoint);
    return config;
  }

  @Override
  public Optional<String> getDescription() {
    return Optional.empty();
  }

  @Override
  public Optional<SchemaDescription> getSchema(String topic, Target target) {
    return Optional.empty();
  }

  @Override
  public boolean canSerialize(String topic, Target target) {
    return getSchemaName(topic, target).map(this::schemaExistsCached).orElse(false);
  }

  @Override
  public Serializer serializer(String topic, Target target) {
    var schemaDefinition = getSchemaName(topic, target)
        .flatMap(this::getSchemaDefinition)
        .orElseThrow(() -> new IllegalStateException(
            String.format("No schema found for topic %s %s", topic, target)));
    DataFormat dataFormat = schemaDefinition.dataFormat();
    UUID schemaVersionId = UUID.fromString(schemaDefinition.schemaVersionId());
    String definition = schemaDefinition.schemaDefinition();
    // converts to format that is expected by serializationFacade
    Function<String, Object> inputConverter = str -> {
      switch (dataFormat) {
        case AVRO:
          return JsonUtil.avroFromJson(str, new Schema.Parser().parse(definition));
        case PROTOBUF:
          return JsonUtil.protoFromJson(str, new ProtobufSchema(definition));
        case JSON:
          return JsonDataWithSchema.builder(definition, str).build();
        default:
          throw new IllegalStateException();
      }
    };
    return input -> serializationFacade.serialize(dataFormat, inputConverter.apply(input), schemaVersionId);
  }

  @Override
  public boolean canDeserialize(String topic, Target target) {
    return !checkSchemaExistenceForDeserialize
        || getSchemaName(topic, target).map(this::schemaExistsCached).orElse(false);
  }

  private Optional<String> findSchemaByPattern(List<Map.Entry<String, Pattern>> patterns, String topicName) {
    return patterns.stream()
        .filter(e -> e.getValue().matcher(topicName).matches())
        .map(Map.Entry::getKey)
        .findFirst();
  }

  private boolean schemaExistsCached(String schemaName) {
    try {
      return schemaExistenceCache.get(schemaName);
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }
  }

  private Optional<String> getSchemaName(String topic, Target target) {
    switch (target) {
      case KEY:
        return findSchemaByPattern(topicKeysSchemas, topic)
            .or(() -> keySchemaNameTemplate == null
                ? Optional.empty()
                : Optional.of(String.format(keySchemaNameTemplate, topic)));
      case VALUE:
        return findSchemaByPattern(topicValuesSchemas, topic)
            .or(() -> Optional.of(String.format(valueSchemaNameTemplate, topic)));
      default:
        return Optional.empty();
    }
  }

  private boolean schemaExists(String schemaName) {
    return getSchema(schemaName).isPresent();
  }

  private Optional<GetSchemaResponse> getSchema(String schemaName) {
    try {
      return Optional.of(
          glueClient.getSchema(
              GetSchemaRequest.builder()
                  .schemaId(
                      SchemaId.builder()
                          .registryName(registryName)
                          .schemaName(schemaName).build())
                  .build())
      );
    } catch (EntityNotFoundException nfe) {
      return Optional.empty();
    }
  }

  private Optional<GetSchemaVersionResponse> getSchemaDefinition(String schemaName) {
    return getSchema(schemaName).flatMap(schemaResponse -> {
      try {
        return Optional.of(
            glueClient.getSchemaVersion(
                GetSchemaVersionRequest.builder()
                    .schemaId(SchemaId.builder().registryName(registryName).schemaName(schemaName).build())
                    .schemaVersionNumber(
                        SchemaVersionNumber.builder().versionNumber(schemaResponse.latestSchemaVersion()).build())
                    .build())
        );
      } catch (EntityNotFoundException nfe) {
        return Optional.empty();
      }
    });
  }

  @Override
  public Deserializer deserializer(String topic, Target target) {
    return new Deserializer() {
      @Override
      public DeserializeResult deserialize(RecordHeaders recordHeaders, byte[] bytes) {
        Object obj =
            deserializationFacade.deserialize(AWSDeserializerInput.builder().buffer(ByteBuffer.wrap(bytes)).build());
        String val = null;
        if (obj instanceof GenericRecord) {
          val = JsonUtil.avroRecordToJson((GenericRecord) obj);
        } else if (obj instanceof DynamicMessage) {
          val = JsonUtil.protoMsgToJson((DynamicMessage) obj);
        } else if (obj instanceof JsonDataWithSchema) {
          val = ((JsonDataWithSchema) obj).getPayload();
        } else {
          throw new IllegalStateException("Unexpected deserialization result: " + obj);
        }
        return new DeserializeResult(val, DeserializeResult.Type.JSON, Map.of());
      }
    };
  }

  @Override
  public void close() {
    glueClient.close();
    deserializationFacade.close();
  }

  private static class FixedDeserializerFactory extends GlueSchemaRegistryDeserializerFactory {

    private static final FixedJsonDeserializer JSON_DESERIALIZER = new FixedJsonDeserializer();

    @Override
    public GlueSchemaRegistryDataFormatDeserializer getInstance(@NotNull DataFormat dataFormat,
                                                                @NotNull GlueSchemaRegistryConfiguration configs) {
      if (dataFormat == DataFormat.JSON) {
        return JSON_DESERIALIZER;
      } else {
        return super.getInstance(dataFormat, configs);
      }
    }
  }

  // We need to override default JsonDeserializer because it tries to instantiate java object
  // when schema has "className" field filled.
  private static class FixedJsonDeserializer implements GlueSchemaRegistryDataFormatDeserializer {

    private static final GlueSchemaRegistryDeserializerDataParser DESERIALIZER_DATA_PARSER =
        GlueSchemaRegistryDeserializerDataParser.getInstance();

    @Override
    public Object deserialize(@NotNull ByteBuffer buffer,
                              @NotNull com.amazonaws.services.schemaregistry.common.Schema schemaObject) {
      String schema = schemaObject.getSchemaDefinition();
      byte[] data = DESERIALIZER_DATA_PARSER.getPlainData(buffer);
      return JsonDataWithSchema.builder(schema, new String(data)).build();
    }
  }

}
