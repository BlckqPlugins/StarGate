/*
 * Copyright 2020 Alemiz
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package alemiz.stargate.events;

import alemiz.stargate.StarGate;
import alemiz.stargate.server.ServerSession;
import dev.waterdog.waterdogpe.event.AsyncEvent;

@AsyncEvent
public class ClientDisconnectedEvent extends ServerEvent {

    public ClientDisconnectedEvent(ServerSession session, StarGate plugin) {
        super(session, plugin);
    }
}
