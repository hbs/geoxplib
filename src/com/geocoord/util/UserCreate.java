package com.geocoord.util;

import com.geocoord.server.ServiceFactory;
import com.geocoord.server.servlet.GuiceBootstrap;
import com.geocoord.thrift.data.User;
import com.geocoord.thrift.data.UserCreateRequest;
import com.geocoord.thrift.data.UserCreateResponse;

public class UserCreate {

  /**
   * @param args
   */
  public static void main(String[] args) throws Exception {    
    
    //
    // Initialize injector
    //
    
    GuiceBootstrap gbs = new GuiceBootstrap();
    gbs.init();
    
    UserCreateRequest request = new UserCreateRequest();
    User user = new User();
    for (int i = 0; i < args.length; i++) {
      user.addToLayerNamespaces(args[i]);
    }
    request.setUser(user);
    UserCreateResponse response = ServiceFactory.getInstance().getUserService().create(request);
    user = response.getUser();
    System.out.println(user);
  }
}
