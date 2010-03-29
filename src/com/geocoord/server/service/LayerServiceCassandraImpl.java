package com.geocoord.server.service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.UUID;

import org.apache.cassandra.thrift.Cassandra;
import org.apache.cassandra.thrift.ColumnOrSuperColumn;
import org.apache.cassandra.thrift.ColumnParent;
import org.apache.cassandra.thrift.ColumnPath;
import org.apache.cassandra.thrift.ConsistencyLevel;
import org.apache.cassandra.thrift.InvalidRequestException;
import org.apache.cassandra.thrift.SlicePredicate;
import org.apache.cassandra.thrift.SliceRange;
import org.apache.cassandra.thrift.TimedOutException;
import org.apache.cassandra.thrift.UnavailableException;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.geocoord.server.ServiceFactory;
import com.geocoord.thrift.data.Constants;
import com.geocoord.thrift.data.GeoCoordException;
import com.geocoord.thrift.data.GeoCoordExceptionCode;
import com.geocoord.thrift.data.Layer;
import com.geocoord.thrift.data.LayerCreateRequest;
import com.geocoord.thrift.data.LayerCreateResponse;
import com.geocoord.thrift.data.LayerRemoveRequest;
import com.geocoord.thrift.data.LayerRemoveResponse;
import com.geocoord.thrift.data.LayerRetrieveRequest;
import com.geocoord.thrift.data.LayerRetrieveResponse;
import com.geocoord.thrift.data.LayerUpdateRequest;
import com.geocoord.thrift.data.LayerUpdateResponse;
import com.geocoord.thrift.services.LayerService;

public class LayerServiceCassandraImpl implements LayerService.Iface {
  
  private static final Logger logger = LoggerFactory.getLogger(LayerServiceCassandraImpl.class);
  
  @Override
  public LayerCreateResponse create(LayerCreateRequest request) throws GeoCoordException, TException {

    Cassandra.Client client = null;
    
    try {
      //
      // Generate a layer id and set the timestamp
      //
      
      Layer layer = request.getLayer();
      layer.setLayerId(UUID.randomUUID().toString());
      layer.setTimestamp(System.currentTimeMillis());
      
      //
      // Generate a HMAC key
      //
      
      layer.setHmacKey(new byte[Constants.LAYER_HMAC_KEY_BYTE_SIZE]);
      ServiceFactory.getInstance().getCryptoHelper().getSecureRandom().nextBytes(layer.getHmacKey());
      
      //
      // Force the user
      //
      
      layer.setUserId(request.getCookie().getUserId());
      
      //
      // Store layer in the Cassandra backend
      //
      
      client = ServiceFactory.getInstance().getCassandraHelper().holdClient(Constants.CASSANDRA_CLUSTER);

      ByteBuffer col = ByteBuffer.allocate(16);
      col.order(ByteOrder.BIG_ENDIAN);
      
      // FIXME(hbs): externalize column name generation
      // Build the column name, i.e. <RTS><NANOOFFSET>
      col.putLong(Long.MAX_VALUE - layer.getTimestamp());
      col.putLong(ServiceFactory.getInstance().getCassandraHelper().getNanoOffset());
      
      byte[] colvalue = ServiceFactory.getInstance().getThriftHelper().serialize(layer);
      
      StringBuilder sb = new StringBuilder(Constants.CASSANDRA_LAYER_ROWKEY_PREFIX);
      sb.append(layer.getLayerId());
      String rowkey = sb.toString();
      
      ColumnPath colpath = new ColumnPath();
      colpath.setColumn_family(Constants.CASSANDRA_HISTORICAL_DATA_COLFAM);
      colpath.setColumn(col.array());
      
      client.insert(Constants.CASSANDRA_KEYSPACE, rowkey, colpath, colvalue, layer.getTimestamp(), ConsistencyLevel.ONE);
      
      //
      // Return the response
      //      
      
      LayerCreateResponse response = new LayerCreateResponse();
      response.setLayer(layer);
      
      return response;
    } catch (InvalidRequestException ire) {
      logger.error("create", ire);
      throw new GeoCoordException(GeoCoordExceptionCode.CASSANDRA_ERROR);
    } catch (TimedOutException toe) {
      logger.error("create", toe);      
      throw new GeoCoordException(GeoCoordExceptionCode.CASSANDRA_ERROR);
    } catch (UnavailableException ue) {
      logger.error("create", ue);
      throw new GeoCoordException(GeoCoordExceptionCode.CASSANDRA_ERROR);
    } catch (TException te) { 
      logger.error("create", te);
      throw new GeoCoordException(GeoCoordExceptionCode.THRIFT_ERROR);      
    } finally {
      //
      // Return the client if needed
      //

      if (null != client) {
        
        ServiceFactory.getInstance().getCassandraHelper().releaseClient(client);        
      }
    }
  }
  

  @Override
  public LayerRetrieveResponse retrieve(LayerRetrieveRequest request) throws GeoCoordException, TException {
    
    Cassandra.Client client = null;
    
    try {
      client = ServiceFactory.getInstance().getCassandraHelper().holdClient(Constants.CASSANDRA_CLUSTER);

      //
      // Retrieve the last version of the layer data
      //
      
      //
      // Read a single column, if it is the same one, then ok, otherwise
      // delete it and return false
      //
      
      StringBuilder sb = new StringBuilder();
      sb.append(Constants.CASSANDRA_LAYER_ROWKEY_PREFIX);
      sb.append(request.getLayerId());
      String rowkey = sb.toString();
      
      SlicePredicate slice = new SlicePredicate();
      
      SliceRange range = new SliceRange();
      range.setCount(1);
      range.setStart(new byte[0]);
      range.setFinish(new byte[0]);

      slice.setSlice_range(range);
      
      ColumnParent colparent = new ColumnParent();
      colparent.setColumn_family(Constants.CASSANDRA_HISTORICAL_DATA_COLFAM);
      
      List<ColumnOrSuperColumn> coscs = client.get_slice(Constants.CASSANDRA_KEYSPACE, rowkey, colparent, slice, ConsistencyLevel.ONE);
      
      if (1 != coscs.size()) {
        throw new GeoCoordException(GeoCoordExceptionCode.LAYER_NOT_FOUND);
      }
            
      //
      // Deserialize data
      //
      
      Layer layer = new Layer();
      ServiceFactory.getInstance().getThriftHelper().deserialize(layer, coscs.get(0).getColumn().getValue());
      
      //
      // If the deleted flag is true, throw an exception
      //
      
      if (layer.isDeleted()) {
        throw new GeoCoordException(GeoCoordExceptionCode.LAYER_DELETED);
      }
      
      LayerRetrieveResponse response = new LayerRetrieveResponse();
      response.setLayer(layer);
      
      return response;
    } catch (InvalidRequestException ire) {
      logger.error("retrieve", ire);
      throw new GeoCoordException(GeoCoordExceptionCode.CASSANDRA_ERROR);
    } catch (TimedOutException toe) {
      logger.error("retrieve", toe);      
      throw new GeoCoordException(GeoCoordExceptionCode.CASSANDRA_ERROR);
    } catch (UnavailableException ue) {
      logger.error("retrieve", ue);
      throw new GeoCoordException(GeoCoordExceptionCode.CASSANDRA_ERROR);
    } catch (TException te) { 
      logger.error("retrieve", te);
      throw new GeoCoordException(GeoCoordExceptionCode.THRIFT_ERROR);      
    } finally {
      //
      // Return the client if needed
      //

      if (null != client) {        
        ServiceFactory.getInstance().getCassandraHelper().releaseClient(client);        
      }
    }
  }
  
  @Override
  public LayerUpdateResponse update(LayerUpdateRequest request) throws GeoCoordException, TException {
    
    Layer layer = request.getLayer();
    
    //
    // Make sure the HMAC key is still set
    //
    
    if (null == layer.getHmacKey() || Constants.LAYER_HMAC_KEY_BYTE_SIZE != layer.getHmacKey().length) {
      throw new GeoCoordException(GeoCoordExceptionCode.LAYER_MISSING_HMAC);
    }
    
    //
    // Store the new version
    //


    Cassandra.Client client = null;
    
    try {
      //
      // Store layer in the Cassandra backend
      //
      
      client = ServiceFactory.getInstance().getCassandraHelper().holdClient(Constants.CASSANDRA_CLUSTER);

      ByteBuffer col = ByteBuffer.allocate(16);
      col.order(ByteOrder.BIG_ENDIAN);
      
      // FIXME(hbs): externalize column name generation
      // Build the column name, i.e. <RTS><NANOOFFSET>
      col.putLong(Long.MAX_VALUE - layer.getTimestamp());
      col.putLong(ServiceFactory.getInstance().getCassandraHelper().getNanoOffset());
      
      byte[] colvalue = ServiceFactory.getInstance().getThriftHelper().serialize(layer);
      
      StringBuilder sb = new StringBuilder(Constants.CASSANDRA_LAYER_ROWKEY_PREFIX);
      sb.append(layer.getLayerId());
      String rowkey = sb.toString();
      
      ColumnPath colpath = new ColumnPath();
      colpath.setColumn_family(Constants.CASSANDRA_HISTORICAL_DATA_COLFAM);
      colpath.setColumn(col.array());
      
      client.insert(Constants.CASSANDRA_KEYSPACE, rowkey, colpath, colvalue, layer.getTimestamp(), ConsistencyLevel.ONE);
      
      //
      // Return the response
      //      
      
      LayerUpdateResponse response = new LayerUpdateResponse();
      response.setLayer(layer);
      
      return response;
    } catch (InvalidRequestException ire) {
      logger.error("update", ire);
      throw new GeoCoordException(GeoCoordExceptionCode.CASSANDRA_ERROR);
    } catch (TimedOutException toe) {
      logger.error("update", toe);      
      throw new GeoCoordException(GeoCoordExceptionCode.CASSANDRA_ERROR);
    } catch (UnavailableException ue) {
      logger.error("update", ue);
      throw new GeoCoordException(GeoCoordExceptionCode.CASSANDRA_ERROR);
    } catch (TException te) { 
      logger.error("update", te);
      throw new GeoCoordException(GeoCoordExceptionCode.THRIFT_ERROR);      
    } finally {
      //
      // Return the client if needed
      //

      if (null != client) {
        
        ServiceFactory.getInstance().getCassandraHelper().releaseClient(client);        
      }
    }

  }
  
  
  @Override
  public LayerRemoveResponse remove(LayerRemoveRequest request) throws GeoCoordException, TException {
    //
    // Insert a deleting marker for this layer
    //
    
    Layer layer = request.getLayer();
      
    // Force the deletion flag
    layer.setDeleted(true);
      
    LayerUpdateRequest lur = new LayerUpdateRequest();
    lur.setLayer(layer);
    update(lur);

    //
    // Return the response
    //      
      
    LayerRemoveResponse response = new LayerRemoveResponse();
    response.setLayer(layer);
    
    return response;
  }
}
