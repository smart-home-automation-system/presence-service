package cloud.cholewa.presence.model;

import java.util.Set;

//an active household member with the MAC addresses of the devices registered for them - the
//service's own view of the registry kept by database-service
public record Member(String name, Set<String> macAddresses) {

    public Member {
        macAddresses = Set.copyOf(macAddresses);
    }
}
