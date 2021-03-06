/*
 * Copyright 2015 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.gcloud.storage;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.gcloud.RetryHelper.runWithRetries;
import static com.google.gcloud.spi.StorageRpc.Option.DELIMITER;
import static com.google.gcloud.spi.StorageRpc.Option.IF_GENERATION_MATCH;
import static com.google.gcloud.spi.StorageRpc.Option.IF_GENERATION_NOT_MATCH;
import static com.google.gcloud.spi.StorageRpc.Option.IF_METAGENERATION_MATCH;
import static com.google.gcloud.spi.StorageRpc.Option.IF_METAGENERATION_NOT_MATCH;
import static com.google.gcloud.spi.StorageRpc.Option.IF_SOURCE_GENERATION_MATCH;
import static com.google.gcloud.spi.StorageRpc.Option.IF_SOURCE_GENERATION_NOT_MATCH;
import static com.google.gcloud.spi.StorageRpc.Option.IF_SOURCE_METAGENERATION_MATCH;
import static com.google.gcloud.spi.StorageRpc.Option.IF_SOURCE_METAGENERATION_NOT_MATCH;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.api.services.storage.model.StorageObject;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import com.google.common.primitives.Ints;
import com.google.gcloud.AuthCredentials.ServiceAccountAuthCredentials;
import com.google.gcloud.BaseService;
import com.google.gcloud.Page;
import com.google.gcloud.PageImpl;
import com.google.gcloud.PageImpl.NextPageFetcher;
import com.google.gcloud.ReadChannel;
import com.google.gcloud.RetryHelper.RetryHelperException;
import com.google.gcloud.spi.StorageRpc;
import com.google.gcloud.spi.StorageRpc.RewriteResponse;
import com.google.gcloud.spi.StorageRpc.Tuple;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.SignatureException;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

final class StorageImpl extends BaseService<StorageOptions> implements Storage {

  private static final byte[] EMPTY_BYTE_ARRAY = {};
  private static final String EMPTY_BYTE_ARRAY_MD5 = "1B2M2Y8AsgTpgAmY7PhCfg==";
  private static final String EMPTY_BYTE_ARRAY_CRC32C = "AAAAAA==";

  private static final Function<Tuple<Storage, Boolean>, Boolean> DELETE_FUNCTION =
      new Function<Tuple<Storage, Boolean>, Boolean>() {
        @Override
        public Boolean apply(Tuple<Storage, Boolean> tuple) {
          return tuple.y();
        }
      };

  private final StorageRpc storageRpc;

  StorageImpl(StorageOptions options) {
    super(options);
    storageRpc = options.rpc();
  }

  @Override
  public Bucket create(BucketInfo bucketInfo, BucketTargetOption... options) {
    final com.google.api.services.storage.model.Bucket bucketPb = bucketInfo.toPb();
    final Map<StorageRpc.Option, ?> optionsMap = optionMap(bucketInfo, options);
    try {
      return Bucket.fromPb(this, runWithRetries(
        new Callable<com.google.api.services.storage.model.Bucket>() {
          @Override
          public com.google.api.services.storage.model.Bucket call() {
            return storageRpc.create(bucketPb, optionsMap);
          }
        }, options().retryParams(), EXCEPTION_HANDLER));
    } catch (RetryHelperException e) {
      throw StorageException.translateAndThrow(e);
    }
  }

  @Override
  public Blob create(BlobInfo blobInfo, BlobTargetOption... options) {
    BlobInfo updatedInfo = blobInfo.toBuilder()
        .md5(EMPTY_BYTE_ARRAY_MD5)
        .crc32c(EMPTY_BYTE_ARRAY_CRC32C)
        .build();
    return create(updatedInfo, new ByteArrayInputStream(EMPTY_BYTE_ARRAY), options);
  }

  @Override
  public Blob create(BlobInfo blobInfo, byte[] content, BlobTargetOption... options) {
    content = firstNonNull(content, EMPTY_BYTE_ARRAY);
    BlobInfo updatedInfo = blobInfo.toBuilder()
        .md5(BaseEncoding.base64().encode(Hashing.md5().hashBytes(content).asBytes()))
        .crc32c(BaseEncoding.base64().encode(
            Ints.toByteArray(Hashing.crc32c().hashBytes(content).asInt())))
        .build();
    return create(updatedInfo, new ByteArrayInputStream(content), options);
  }

  @Override
  public Blob create(BlobInfo blobInfo, InputStream content, BlobWriteOption... options) {
    Tuple<BlobInfo, BlobTargetOption[]> targetOptions = BlobTargetOption.convert(blobInfo, options);
    return create(targetOptions.x(), content, targetOptions.y());
  }

  private Blob create(BlobInfo info, final InputStream content, BlobTargetOption... options) {
    final StorageObject blobPb = info.toPb();
    final Map<StorageRpc.Option, ?> optionsMap = optionMap(info, options);
    try {
      return Blob.fromPb(this, runWithRetries(new Callable<StorageObject>() {
        @Override
        public StorageObject call() {
          return storageRpc.create(blobPb,
              firstNonNull(content, new ByteArrayInputStream(EMPTY_BYTE_ARRAY)), optionsMap);
        }
      }, options().retryParams(), EXCEPTION_HANDLER));
    } catch (RetryHelperException e) {
      throw StorageException.translateAndThrow(e);
    }
  }

  @Override
  public Bucket get(String bucket, BucketGetOption... options) {
    final com.google.api.services.storage.model.Bucket bucketPb = BucketInfo.of(bucket).toPb();
    final Map<StorageRpc.Option, ?> optionsMap = optionMap(options);
    try {
      com.google.api.services.storage.model.Bucket answer = runWithRetries(
          new Callable<com.google.api.services.storage.model.Bucket>() {
            @Override
            public com.google.api.services.storage.model.Bucket call() {
              return storageRpc.get(bucketPb, optionsMap);
            }
          }, options().retryParams(), EXCEPTION_HANDLER);
      return answer == null ? null : Bucket.fromPb(this, answer);
    } catch (RetryHelperException e) {
      throw StorageException.translateAndThrow(e);
    }
  }

  @Override
  public Blob get(String bucket, String blob, BlobGetOption... options) {
    return get(BlobId.of(bucket, blob), options);
  }

  @Override
  public Blob get(BlobId blob, BlobGetOption... options) {
    final StorageObject storedObject = blob.toPb();
    final Map<StorageRpc.Option, ?> optionsMap = optionMap(blob, options);
    try {
      StorageObject storageObject = runWithRetries(new Callable<StorageObject>() {
        @Override
        public StorageObject call() {
          return storageRpc.get(storedObject, optionsMap);
        }
      }, options().retryParams(), EXCEPTION_HANDLER);
      return storageObject == null ? null : Blob.fromPb(this, storageObject);
    } catch (RetryHelperException e) {
      throw StorageException.translateAndThrow(e);
    }
  }

  @Override
  public Blob get(BlobId blob) {
    return get(blob, new BlobGetOption[0]);
  }

  private static class BucketPageFetcher implements NextPageFetcher<Bucket> {

    private static final long serialVersionUID = 5850406828803613729L;
    private final Map<StorageRpc.Option, ?> requestOptions;
    private final StorageOptions serviceOptions;

    BucketPageFetcher(
        StorageOptions serviceOptions, String cursor,
        Map<StorageRpc.Option, ?> optionMap) {
      this.requestOptions =
          PageImpl.nextRequestOptions(StorageRpc.Option.PAGE_TOKEN, cursor, optionMap);
      this.serviceOptions = serviceOptions;
    }

    @Override
    public Page<Bucket> nextPage() {
      return listBuckets(serviceOptions, requestOptions);
    }
  }

  private static class BlobPageFetcher implements NextPageFetcher<Blob> {

    private static final long serialVersionUID = 81807334445874098L;
    private final Map<StorageRpc.Option, ?> requestOptions;
    private final StorageOptions serviceOptions;
    private final String bucket;

    BlobPageFetcher(String bucket, StorageOptions serviceOptions, String cursor,
        Map<StorageRpc.Option, ?> optionMap) {
      this.requestOptions =
          PageImpl.nextRequestOptions(StorageRpc.Option.PAGE_TOKEN, cursor, optionMap);
      this.serviceOptions = serviceOptions;
      this.bucket = bucket;
    }

    @Override
    public Page<Blob> nextPage() {
      return listBlobs(bucket, serviceOptions, requestOptions);
    }
  }

  @Override
  public Page<Bucket> list(BucketListOption... options) {
    return listBuckets(options(), optionMap(options));
  }

  @Override
  public Page<Blob> list(final String bucket, BlobListOption... options) {
    return listBlobs(bucket, options(), optionMap(options));
  }

  private static Page<Bucket> listBuckets(final StorageOptions serviceOptions,
      final Map<StorageRpc.Option, ?> optionsMap) {
    try {
      Tuple<String, Iterable<com.google.api.services.storage.model.Bucket>> result = runWithRetries(
          new Callable<Tuple<String, Iterable<com.google.api.services.storage.model.Bucket>>>() {
            @Override
            public Tuple<String, Iterable<com.google.api.services.storage.model.Bucket>> call() {
              return serviceOptions.rpc().list(optionsMap);
            }
          }, serviceOptions.retryParams(), EXCEPTION_HANDLER);
      String cursor = result.x();
      Iterable<Bucket> buckets =
          result.y() == null ? ImmutableList.<Bucket>of() : Iterables.transform(result.y(),
              new Function<com.google.api.services.storage.model.Bucket, Bucket>() {
                @Override
                public Bucket apply(com.google.api.services.storage.model.Bucket bucketPb) {
                  return Bucket.fromPb(serviceOptions.service(), bucketPb);
                }
              });
      return new PageImpl<>(
          new BucketPageFetcher(serviceOptions, cursor, optionsMap), cursor,
          buckets);
    } catch (RetryHelperException e) {
      throw StorageException.translateAndThrow(e);
    }
  }

  private static Page<Blob> listBlobs(final String bucket,
      final StorageOptions serviceOptions, final Map<StorageRpc.Option, ?> optionsMap) {
    try {
      Tuple<String, Iterable<StorageObject>> result = runWithRetries(
          new Callable<Tuple<String, Iterable<StorageObject>>>() {
            @Override
            public Tuple<String, Iterable<StorageObject>> call() {
              return serviceOptions.rpc().list(bucket, optionsMap);
            }
          }, serviceOptions.retryParams(), EXCEPTION_HANDLER);
      String cursor = result.x();
      Iterable<Blob> blobs =
          result.y() == null
              ? ImmutableList.<Blob>of()
              : Iterables.transform(result.y(), new Function<StorageObject, Blob>() {
                @Override
                public Blob apply(StorageObject storageObject) {
                  return Blob.fromPb(serviceOptions.service(), storageObject);
                }
              });
      return new PageImpl<>(
          new BlobPageFetcher(bucket, serviceOptions, cursor, optionsMap),
          cursor,
          blobs);
    } catch (RetryHelperException e) {
      throw StorageException.translateAndThrow(e);
    }
  }

  @Override
  public Bucket update(BucketInfo bucketInfo, BucketTargetOption... options) {
    final com.google.api.services.storage.model.Bucket bucketPb = bucketInfo.toPb();
    final Map<StorageRpc.Option, ?> optionsMap = optionMap(bucketInfo, options);
    try {
      return Bucket.fromPb(this, runWithRetries(
          new Callable<com.google.api.services.storage.model.Bucket>() {
            @Override
            public com.google.api.services.storage.model.Bucket call() {
              return storageRpc.patch(bucketPb, optionsMap);
            }
          }, options().retryParams(), EXCEPTION_HANDLER));
    } catch (RetryHelperException e) {
      throw StorageException.translateAndThrow(e);
    }
  }

  @Override
  public Blob update(BlobInfo blobInfo, BlobTargetOption... options) {
    final StorageObject storageObject = blobInfo.toPb();
    final Map<StorageRpc.Option, ?> optionsMap = optionMap(blobInfo, options);
    try {
      return Blob.fromPb(this, runWithRetries(new Callable<StorageObject>() {
        @Override
        public StorageObject call() {
          return storageRpc.patch(storageObject, optionsMap);
        }
      }, options().retryParams(), EXCEPTION_HANDLER));
    } catch (RetryHelperException e) {
      throw StorageException.translateAndThrow(e);
    }
  }

  @Override
  public Blob update(BlobInfo blobInfo) {
    return update(blobInfo, new BlobTargetOption[0]);
  }

  @Override
  public boolean delete(String bucket, BucketSourceOption... options) {
    final com.google.api.services.storage.model.Bucket bucketPb = BucketInfo.of(bucket).toPb();
    final Map<StorageRpc.Option, ?> optionsMap = optionMap(options);
    try {
      return runWithRetries(new Callable<Boolean>() {
        @Override
        public Boolean call() {
          return storageRpc.delete(bucketPb, optionsMap);
        }
      }, options().retryParams(), EXCEPTION_HANDLER);
    } catch (RetryHelperException e) {
      throw StorageException.translateAndThrow(e);
    }
  }

  @Override
  public boolean delete(String bucket, String blob, BlobSourceOption... options) {
    return delete(BlobId.of(bucket, blob), options);
  }

  @Override
  public boolean delete(BlobId blob, BlobSourceOption... options) {
    final StorageObject storageObject = blob.toPb();
    final Map<StorageRpc.Option, ?> optionsMap = optionMap(blob, options);
    try {
      return runWithRetries(new Callable<Boolean>() {
        @Override
        public Boolean call() {
          return storageRpc.delete(storageObject, optionsMap);
        }
      }, options().retryParams(), EXCEPTION_HANDLER);
    } catch (RetryHelperException e) {
      throw StorageException.translateAndThrow(e);
    }
  }

  @Override
  public boolean delete(BlobId blob) {
    return delete(blob, new BlobSourceOption[0]);
  }

  @Override
  public Blob compose(final ComposeRequest composeRequest) {
    final List<StorageObject> sources =
        Lists.newArrayListWithCapacity(composeRequest.sourceBlobs().size());
    for (ComposeRequest.SourceBlob sourceBlob : composeRequest.sourceBlobs()) {
      sources.add(BlobInfo.builder(
          BlobId.of(composeRequest.target().bucket(), sourceBlob.name(), sourceBlob.generation()))
              .build().toPb());
    }
    final StorageObject target = composeRequest.target().toPb();
    final Map<StorageRpc.Option, ?> targetOptions = optionMap(composeRequest.target().generation(),
        composeRequest.target().metageneration(), composeRequest.targetOptions());
    try {
      return Blob.fromPb(this, runWithRetries(new Callable<StorageObject>() {
        @Override
        public StorageObject call() {
          return storageRpc.compose(sources, target, targetOptions);
        }
      }, options().retryParams(), EXCEPTION_HANDLER));
    } catch (RetryHelperException e) {
      throw StorageException.translateAndThrow(e);
    }
  }

  @Override
  public CopyWriter copy(final CopyRequest copyRequest) {
    final StorageObject source = copyRequest.source().toPb();
    final Map<StorageRpc.Option, ?> sourceOptions =
        optionMap(copyRequest.source().generation(), null, copyRequest.sourceOptions(), true);
    final StorageObject target = copyRequest.target().toPb();
    final Map<StorageRpc.Option, ?> targetOptions = optionMap(copyRequest.target().generation(),
        copyRequest.target().metageneration(), copyRequest.targetOptions());
    try {
      RewriteResponse rewriteResponse = runWithRetries(new Callable<RewriteResponse>() {
        @Override
        public RewriteResponse call() {
          return storageRpc.openRewrite(new StorageRpc.RewriteRequest(source, sourceOptions, target,
              targetOptions, copyRequest.megabytesCopiedPerChunk()));
        }
      }, options().retryParams(), EXCEPTION_HANDLER);
      return new CopyWriter(options(), rewriteResponse);
    } catch (RetryHelperException e) {
      throw StorageException.translateAndThrow(e);
    }
  }

  @Override
  public byte[] readAllBytes(String bucket, String blob, BlobSourceOption... options) {
    return readAllBytes(BlobId.of(bucket, blob), options);
  }

  @Override
  public byte[] readAllBytes(BlobId blob, BlobSourceOption... options) {
    final StorageObject storageObject = blob.toPb();
    final Map<StorageRpc.Option, ?> optionsMap = optionMap(blob, options);
    try {
      return runWithRetries(new Callable<byte[]>() {
        @Override
        public byte[] call() {
          return storageRpc.load(storageObject, optionsMap);
        }
      }, options().retryParams(), EXCEPTION_HANDLER);
    } catch (RetryHelperException e) {
      throw StorageException.translateAndThrow(e);
    }
  }

  @Override
  public BatchResponse submit(BatchRequest batchRequest) {
    List<Tuple<StorageObject, Map<StorageRpc.Option, ?>>> toDelete =
        Lists.newArrayListWithCapacity(batchRequest.toDelete().size());
    for (Map.Entry<BlobId, Iterable<BlobSourceOption>> entry : batchRequest.toDelete().entrySet()) {
      BlobId blob = entry.getKey();
      Map<StorageRpc.Option, ?> optionsMap = optionMap(blob.generation(), null, entry.getValue());
      StorageObject storageObject = blob.toPb();
      toDelete.add(Tuple.<StorageObject, Map<StorageRpc.Option, ?>>of(storageObject, optionsMap));
    }
    List<Tuple<StorageObject, Map<StorageRpc.Option, ?>>> toUpdate =
        Lists.newArrayListWithCapacity(batchRequest.toUpdate().size());
    for (Map.Entry<BlobInfo, Iterable<BlobTargetOption>> entry :
        batchRequest.toUpdate().entrySet()) {
      BlobInfo blobInfo = entry.getKey();
      Map<StorageRpc.Option, ?> optionsMap =
          optionMap(blobInfo.generation(), blobInfo.metageneration(), entry.getValue());
      toUpdate.add(Tuple.<StorageObject, Map<StorageRpc.Option, ?>>of(blobInfo.toPb(), optionsMap));
    }
    List<Tuple<StorageObject, Map<StorageRpc.Option, ?>>> toGet =
        Lists.newArrayListWithCapacity(batchRequest.toGet().size());
    for (Map.Entry<BlobId, Iterable<BlobGetOption>> entry : batchRequest.toGet().entrySet()) {
      BlobId blob = entry.getKey();
      Map<StorageRpc.Option, ?> optionsMap = optionMap(blob.generation(), null, entry.getValue());
      toGet.add(Tuple.<StorageObject, Map<StorageRpc.Option, ?>>of(blob.toPb(), optionsMap));
    }
    StorageRpc.BatchResponse response =
        storageRpc.batch(new StorageRpc.BatchRequest(toDelete, toUpdate, toGet));
    List<BatchResponse.Result<Boolean>> deletes =
        transformBatchResult(toDelete, response.deletes, DELETE_FUNCTION);
    List<BatchResponse.Result<Blob>> updates =
        transformBatchResult(toUpdate, response.updates, Blob.BLOB_FROM_PB_FUNCTION);
    List<BatchResponse.Result<Blob>> gets =
        transformBatchResult(toGet, response.gets, Blob.BLOB_FROM_PB_FUNCTION);
    return new BatchResponse(deletes, updates, gets);
  }

  private <I, O extends Serializable> List<BatchResponse.Result<O>> transformBatchResult(
      Iterable<Tuple<StorageObject, Map<StorageRpc.Option, ?>>> request,
      Map<StorageObject, Tuple<I, StorageException>> results,
      Function<Tuple<Storage, I>, O> transform) {
    List<BatchResponse.Result<O>> response = Lists.newArrayListWithCapacity(results.size());
    for (Tuple<StorageObject, ?> tuple : request) {
      Tuple<I, StorageException> result = results.get(tuple.x());
      I object = result.x();
      StorageException exception = result.y();
      if (exception != null) {
        response.add(new BatchResponse.Result<O>(exception));
      } else {
        response.add(object != null
            ? BatchResponse.Result.of(transform.apply(Tuple.of((Storage) this, object)))
            : BatchResponse.Result.<O>empty());
      }
    }
    return response;
  }

  @Override
  public ReadChannel reader(String bucket, String blob, BlobSourceOption... options) {
    Map<StorageRpc.Option, ?> optionsMap = optionMap(options);
    return new BlobReadChannel(options(), BlobId.of(bucket, blob), optionsMap);
  }

  @Override
  public ReadChannel reader(BlobId blob, BlobSourceOption... options) {
    Map<StorageRpc.Option, ?> optionsMap = optionMap(blob, options);
    return new BlobReadChannel(options(), blob, optionsMap);
  }

  @Override
  public BlobWriteChannel writer(BlobInfo blobInfo, BlobWriteOption... options) {
    Tuple<BlobInfo, BlobTargetOption[]> targetOptions = BlobTargetOption.convert(blobInfo, options);
    return writer(targetOptions.x(), targetOptions.y());
  }

  private BlobWriteChannel writer(BlobInfo blobInfo, BlobTargetOption... options) {
    final Map<StorageRpc.Option, ?> optionsMap = optionMap(blobInfo, options);
    return new BlobWriteChannel(options(), blobInfo, optionsMap);
  }

  @Override
  public URL signUrl(BlobInfo blobInfo, long duration, TimeUnit unit, SignUrlOption... options) {
    EnumMap<SignUrlOption.Option, Object> optionMap = Maps.newEnumMap(SignUrlOption.Option.class);
    for (SignUrlOption option : options) {
      optionMap.put(option.option(), option.value());
    }
    ServiceAccountAuthCredentials authCred =
        (ServiceAccountAuthCredentials) optionMap.get(SignUrlOption.Option.SERVICE_ACCOUNT_CRED);
    ServiceAccountCredentials cred = authCred != null ? authCred.credentials() : null;
    if (authCred == null) {
      checkArgument(
          this.options().authCredentials() != null
          && this.options().authCredentials().credentials() instanceof ServiceAccountCredentials,
          "Signing key was not provided and could not be derived");
      cred = (ServiceAccountCredentials) this.options().authCredentials().credentials();
    }
    // construct signature - see https://cloud.google.com/storage/docs/access-control#Signed-URLs
    StringBuilder stBuilder = new StringBuilder();
    if (optionMap.containsKey(SignUrlOption.Option.HTTP_METHOD)) {
      stBuilder.append(optionMap.get(SignUrlOption.Option.HTTP_METHOD));
    } else {
      stBuilder.append(HttpMethod.GET);
    }
    stBuilder.append('\n');
    if (firstNonNull((Boolean) optionMap.get(SignUrlOption.Option.MD5), false)) {
      checkArgument(blobInfo.md5() != null, "Blob is missing a value for md5");
      stBuilder.append(blobInfo.md5());
    }
    stBuilder.append('\n');
    if (firstNonNull((Boolean) optionMap.get(SignUrlOption.Option.CONTENT_TYPE), false)) {
      checkArgument(blobInfo.contentType() != null, "Blob is missing a value for content-type");
      stBuilder.append(blobInfo.contentType());
    }
    stBuilder.append('\n');
    long expiration = TimeUnit.SECONDS.convert(
        options().clock().millis() + unit.toMillis(duration), TimeUnit.MILLISECONDS);
    stBuilder.append(expiration).append('\n');
    StringBuilder path = new StringBuilder();
    if (!blobInfo.bucket().startsWith("/")) {
      path.append('/');
    }
    path.append(blobInfo.bucket());
    if (!blobInfo.bucket().endsWith("/")) {
      path.append('/');
    }
    if (blobInfo.name().startsWith("/")) {
      path.setLength(stBuilder.length() - 1);
    }
    path.append(blobInfo.name());
    stBuilder.append(path);
    try {
      Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initSign(cred.getPrivateKey());
      signer.update(stBuilder.toString().getBytes(UTF_8));
      stBuilder = new StringBuilder("https://storage.googleapis.com").append(path);
      String signature =
          URLEncoder.encode(BaseEncoding.base64().encode(signer.sign()), UTF_8.name());
      stBuilder.append("?GoogleAccessId=").append(cred.getClientEmail());
      stBuilder.append("&Expires=").append(expiration);
      stBuilder.append("&Signature=").append(signature);
      return new URL(stBuilder.toString());
    } catch (MalformedURLException | NoSuchAlgorithmException | UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    } catch (SignatureException | InvalidKeyException e) {
      throw new IllegalArgumentException("Invalid service account private key");
    }
  }

  @Override
  public List<Blob> get(BlobId... blobIds) {
    BatchRequest.Builder requestBuilder = BatchRequest.builder();
    for (BlobId blob : blobIds) {
      requestBuilder.get(blob);
    }
    BatchResponse response = submit(requestBuilder.build());
    return Collections.unmodifiableList(transformResultList(response.gets(), null));
  }

  @Override
  public List<Blob> update(BlobInfo... blobInfos) {
    BatchRequest.Builder requestBuilder = BatchRequest.builder();
    for (BlobInfo blobInfo : blobInfos) {
      requestBuilder.update(blobInfo);
    }
    BatchResponse response = submit(requestBuilder.build());
    return Collections.unmodifiableList(transformResultList(response.updates(), null));
  }

  @Override
  public List<Boolean> delete(BlobId... blobIds) {
    BatchRequest.Builder requestBuilder = BatchRequest.builder();
    for (BlobId blob : blobIds) {
      requestBuilder.delete(blob);
    }
    BatchResponse response = submit(requestBuilder.build());
    return Collections.unmodifiableList(transformResultList(response.deletes(), Boolean.FALSE));
  }

  private static <T extends Serializable> List<T> transformResultList(
      List<BatchResponse.Result<T>> results, final T errorValue) {
    return Lists.transform(results, new Function<BatchResponse.Result<T>, T>() {
      @Override
      public T apply(BatchResponse.Result<T> result) {
        return result.failed() ? errorValue : result.get();
      }
    });
  }

  private static <T> void addToOptionMap(StorageRpc.Option option, T defaultValue,
      Map<StorageRpc.Option, Object> map) {
    addToOptionMap(option, option, defaultValue, map);
  }

  private static <T> void addToOptionMap(StorageRpc.Option getOption, StorageRpc.Option putOption,
      T defaultValue, Map<StorageRpc.Option, Object> map) {
    if (map.containsKey(getOption)) {
      @SuppressWarnings("unchecked")
      T value = (T) map.remove(getOption);
      checkArgument(value != null || defaultValue != null,
          "Option " + getOption.value() + " is missing a value");
      value = firstNonNull(value, defaultValue);
      map.put(putOption, value);
    }
  }

  private Map<StorageRpc.Option, ?> optionMap(Long generation, Long metaGeneration,
      Iterable<? extends Option> options) {
    return optionMap(generation, metaGeneration, options, false);
  }

  private Map<StorageRpc.Option, ?> optionMap(Long generation, Long metaGeneration,
      Iterable<? extends Option> options, boolean useAsSource) {
    Map<StorageRpc.Option, Object> temp = Maps.newEnumMap(StorageRpc.Option.class);
    for (Option option : options) {
      Object prev = temp.put(option.rpcOption(), option.value());
      checkArgument(prev == null, "Duplicate option %s", option);
    }
    Boolean value = (Boolean) temp.remove(DELIMITER);
    if (Boolean.TRUE.equals(value)) {
      temp.put(DELIMITER, options().pathDelimiter());
    }
    if (useAsSource) {
      addToOptionMap(IF_GENERATION_MATCH, IF_SOURCE_GENERATION_MATCH, generation, temp);
      addToOptionMap(IF_GENERATION_NOT_MATCH, IF_SOURCE_GENERATION_NOT_MATCH, generation, temp);
      addToOptionMap(IF_METAGENERATION_MATCH, IF_SOURCE_METAGENERATION_MATCH, metaGeneration, temp);
      addToOptionMap(IF_METAGENERATION_NOT_MATCH,
          IF_SOURCE_METAGENERATION_NOT_MATCH, metaGeneration, temp);
    } else {
      addToOptionMap(IF_GENERATION_MATCH, generation, temp);
      addToOptionMap(IF_GENERATION_NOT_MATCH, generation, temp);
      addToOptionMap(IF_METAGENERATION_MATCH, metaGeneration, temp);
      addToOptionMap(IF_METAGENERATION_NOT_MATCH, metaGeneration, temp);
    }
    return ImmutableMap.copyOf(temp);
  }

  private Map<StorageRpc.Option, ?> optionMap(Option... options) {
    return optionMap(null, null, Arrays.asList(options));
  }

  private Map<StorageRpc.Option, ?> optionMap(Long generation, Long metaGeneration,
      Option... options) {
    return optionMap(generation, metaGeneration, Arrays.asList(options));
  }

  private Map<StorageRpc.Option, ?> optionMap(BucketInfo bucketInfo, Option... options) {
    return optionMap(null, bucketInfo.metageneration(), options);
  }

  private Map<StorageRpc.Option, ?> optionMap(BlobInfo blobInfo, Option... options) {
    return optionMap(blobInfo.generation(), blobInfo.metageneration(), options);
  }

  private Map<StorageRpc.Option, ?> optionMap(BlobId blobId, Option... options) {
    return optionMap(blobId.generation(), null, options);
  }
}
