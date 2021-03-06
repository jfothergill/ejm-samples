package ejm.chapter7.message;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.enterprise.concurrent.ManagedExecutorService;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientRequestFilter;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.HttpHeaders;

import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;
import org.keycloak.authorization.client.AuthzClient;
import org.wildfly.swarm.topology.Topology;

/**
 * @author Ken Finnigan
 */
@Path("/")
public class MessageResource {

    private Topology topology;

    private AuthzClient authzClient;

    public MessageResource() {
        try {
            topology = Topology.lookup();
        } catch (NamingException e) {
            e.printStackTrace();
        }
    }

    @GET
    @Path("/sync")
    public String getMessageSync() throws Exception {
        ResteasyClient client = new ResteasyClientBuilder().build();

        client.register(new ClientRequestFilter() {
            @Override
            public void filter(ClientRequestContext clientRequestContext) throws IOException {
                List<Object> list = new ArrayList<>();
                list.add("Bearer " + getAuthzClient().obtainAccessToken().getToken());
                clientRequestContext.getHeaders().put(HttpHeaders.AUTHORIZATION, list);
            }
        });

        URI url = getService("time");
        ResteasyWebTarget target = client.target(url);

        TimeService timeService = target.proxy(TimeService.class);
        String time = timeService.getTime();

        return message(time, url.toString());
    }

    @GET
    @Path("/async")
    public void getMessageAsync(@Suspended final AsyncResponse asyncResponse) throws Exception {
        executorService().execute(() -> {
            ResteasyClient client = new ResteasyClientBuilder().build();
            URI url = null;
            try {
                url = getService("time");
            } catch (Exception e) {
                asyncResponse.resume(e);
            }

            ResteasyWebTarget target = client.target(url);

            TimeService timeService = target.proxy(TimeService.class);
            String time = timeService.getTime();

            asyncResponse.resume(message(time, url.toString()));
        });
    }

    private URI getService(String name) throws Exception {
        Map<String, List<Topology.Entry>> map = this.topology.asMap();

        if (map.isEmpty()) {
            throw new Exception("Service not found for '" + name + "'");
        }

        Optional<Topology.Entry> seOptional = map
                .get(name)
                .stream()
                .findFirst();

        Topology.Entry serviceEntry = seOptional.orElseThrow(() -> new Exception("Service not found for '" + name + "'"));

        return new URI("http", null, serviceEntry.getAddress(), serviceEntry.getPort(), null, null, null);
    }

    private String message(String time, String url) {
        return "The date and time at " + url + " is " + time;
    }

    private AuthzClient getAuthzClient() {
        if (this.authzClient == null) {
            try {
                this.authzClient = AuthzClient.create();
            } catch (Exception e) {
                throw new RuntimeException("Could not create authorization client.", e);
            }
        }

        return this.authzClient;
    }

    private ManagedExecutorService executorService() throws Exception {
        InitialContext ctx = new InitialContext();
        return (ManagedExecutorService) ctx.lookup("java:jboss/ee/concurrency/executor/default");
    }
}
