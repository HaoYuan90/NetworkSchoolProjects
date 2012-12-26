#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <netdb.h>
#include <limits.h>

#define NBR_ROUTER 5

typedef struct {
    unsigned int router_id;
}pkt_INIT;

typedef struct {
    unsigned int router_id;
    unsigned int link_id;
}pkt_HELLO;

typedef struct {
    unsigned int sender;
    unsigned int router_id;
    unsigned int link_id;
    unsigned int cost;
    unsigned int via;
}pkt_LSPDU;

typedef struct {
    unsigned int link;
    unsigned int cost;
}link_cost;

typedef struct {
    unsigned int nbr_link;
    link_cost linkcost[NBR_ROUTER];
}circuit_DB;

typedef enum {
    INIT_s,
    CIRCUIT_DB_r,
    HELLO_s,
    HELLO_r,
    LSPDU_s,
    LSPDU_r,
    LSPDU_repeat
}msg_type;

void error(char*);
void logmessage(msg_type,int[],char*,int);
void logtopology(circuit_DB[],int,char*);
void logRIB(int[],int[],int,char*);
int updateTopology(circuit_DB[],pkt_LSPDU);
void constructLSPDU(int*, pkt_LSPDU[], circuit_DB,int);
void sendLSPDU(int, struct sockaddr_in, pkt_LSPDU[], int,int,char*);
void forwardLSPDU(int, struct sockaddr_in, pkt_LSPDU, int[],int, int,char*);
void shortestPath(circuit_DB[], int[], int [], int);

int main (int argc, char **argv)
{
    int routerid;
    unsigned short nport, rport;
    char* hostname;
    if(argc != 5){
        printf("Wrong number of parameters, please refer to README for more info\n");
        return 0;
    }
    routerid = atoi(argv[1]);
    hostname = argv[2];
    nport = atoi(argv[3]);
    rport = atoi(argv[4]);

    //init sending UDP socket
    int sock;
    if((sock = socket(AF_INET,SOCK_DGRAM,0)) <0)
        error("socket() failed");

    //construct the rounter address structure and bind the socket
    struct sockaddr_in router_addr;
    memset(&router_addr,0,sizeof router_addr);
    router_addr.sin_family = AF_INET;
    router_addr.sin_addr.s_addr = INADDR_ANY;
    router_addr.sin_port = htons(rport);

    if(bind(sock, (struct sockaddr*) &router_addr,sizeof router_addr)<0)
        error("ERROR on binding");

    //construct nse address structure
    struct sockaddr_in nse_addr;
    struct hostent *nse;
    nse = gethostbyname(hostname);
    memset(&nse_addr, 0, sizeof nse_addr);
    nse_addr.sin_family = AF_INET;
    bcopy((char *)nse->h_addr,(char*)&nse_addr.sin_addr.s_addr,nse->h_length);
    nse_addr.sin_port = htons(nport);

    //initialise file
    char filename[20];
    sprintf(filename,"router%d.log",routerid);
    FILE *fp = fopen(filename,"w+");
    fclose(fp);

    //send init
    pkt_INIT pkt_init;
    pkt_init.router_id = routerid;
    if(sendto(sock,&pkt_init,sizeof pkt_init,0,(struct sockaddr*) &nse_addr,sizeof nse_addr)<0)
        error("sendto() failed at init");
    logmessage(INIT_s,NULL,filename,routerid);

    //wait for circuit_DB
    int buffer[1024] = {0};
    struct sockaddr_in from;
    memset(&from, 0, sizeof from);
    unsigned int fromlen = 0;
    if(recvfrom(sock,buffer,1024,0,(struct sockaddr *)&from,&fromlen)<0)
        error("recvfrom() failed at circuitDB");
    logmessage(CIRCUIT_DB_r,NULL,filename,routerid);

    //initialise router table
    int i;
    circuit_DB routers[NBR_ROUTER];
    for(i=0;i<NBR_ROUTER;i++)
        routers[i].nbr_link = 0;

    circuit_DB* self;
    self = (circuit_DB*)buffer;
    //initialise data of self
    memcpy(&routers[routerid-1],self,sizeof (circuit_DB));
    logtopology(routers,routerid,filename);

    //sending hello packets
    for(i=0;i<routers[routerid-1].nbr_link;i++){
        pkt_HELLO pkt_hello;
        pkt_hello.router_id = routerid;
        pkt_hello.link_id = routers[routerid-1].linkcost[i].link;
        if(sendto(sock,&pkt_hello,sizeof pkt_hello,0,(struct sockaddr*) &nse_addr, sizeof nse_addr)<0)
            error("sendto() failed at hello");
        logmessage(HELLO_s, (int*)&pkt_hello,filename,routerid);
    }

    //init links via which hello packets are received
    int hellolinks[NBR_ROUTER];
    int numhello=0;
    //initialise LSPDU packets
    int num_LSPDU;
    pkt_LSPDU LSPDU[NBR_ROUTER];
    constructLSPDU(&num_LSPDU, LSPDU, routers[routerid-1], routerid);

    //initialise shortest distance table and topology table
    int topo[NBR_ROUTER], dist[NBR_ROUTER];
    //receive packets
    //timeout of 2 seconds
    struct timeval socketTimeOut;
    socketTimeOut.tv_sec = 2;
    socketTimeOut.tv_usec = 0;
    //set i/o stream to monitor
    fd_set socketReadFDs;
    fd_set socketExcepFDs;
    FD_ZERO(&socketReadFDs);
    FD_ZERO(&socketExcepFDs);
    FD_SET(sock, &socketReadFDs);
    FD_SET(sock, &socketExcepFDs);

    while (1){
        int t = select(sock+1, &socketReadFDs, NULL, &socketExcepFDs, &socketTimeOut);
        if (t == -1) {
            error("select() failed");
        }
        if (t != 0) {
            if (FD_ISSET (sock, &socketExcepFDs)) {
                error("unexpected exception at select()");
            }
            if (FD_ISSET(sock, &socketReadFDs)) {
                //there is data in input stream to be read
                memset(buffer, 0, sizeof (int)*1024);
                if(recvfrom(sock,buffer,1024,0,(struct sockaddr *)&from, &fromlen)<0)
                    error("recvfrom() failed at LSPDU");
                //determine the type of packet received
                pkt_LSPDU* temp = (pkt_LSPDU*)buffer;
                if(temp->link_id == 0){
                    pkt_HELLO *hello = (pkt_HELLO*)buffer;
                    hellolinks[numhello] = hello->link_id;
                    numhello ++;
                    //packet is a hello, respond with LSPDU
                    logmessage(HELLO_r, buffer,filename,routerid);
                    sendLSPDU(sock,nse_addr,LSPDU,num_LSPDU,hello->link_id,filename);
                }
                else{
                    //packet is LSPDU, update topology and run dijkstra algorithm
                    logmessage(LSPDU_r, buffer,filename,routerid);
                    if(updateTopology(routers,*temp)){
                        shortestPath(routers,topo,dist,routerid);
                        logtopology(routers,routerid,filename);
                        logRIB(topo,dist,routerid,filename);
                        forwardLSPDU(sock, nse_addr, *temp, hellolinks, numhello, routerid,filename);
                    }
                    else{
                        logmessage(LSPDU_repeat,NULL,filename,routerid);
                    }
                }
            }
        }
        else {
            //socket time out, terminate program
            return 0;
        }
    }

    return 0;
}

void error(char* msg)
{
    perror(msg);
    exit(1);
}

void logtopology(circuit_DB routers[],int routerid,char* filename){
    FILE *fp = fopen(filename,"a");
    fprintf(fp,"# Topology database\n");
    int i,j;
    for(i =0;i<NBR_ROUTER;i++){
        if(routers[i].nbr_link != 0){
            fprintf(fp,"R%d -> R%d nbr link %d\n",routerid, i+1, routers[i].nbr_link);
            for(j=0;j<routers[i].nbr_link;j++){
                fprintf(fp, "R%d -> R%d link %d cost %d\n",routerid, i+1, routers[i].linkcost[j].link,routers[i].linkcost[j].cost);
            }
        }
    }
    fclose(fp);
}

void logRIB(int topo[],int dist[],int routerid,char* filename){
    FILE *fp = fopen(filename, "a");
    fprintf(fp,"# RIB\n");
    int i;
    for(i=0;i<NBR_ROUTER;i++){
        if(dist[i] != INT_MAX){
            if(topo[i] == -1)
                fprintf(fp, "R%d -> R%d -> local, 0\n",routerid,i+1);
            else{
                int prev,next = i;
                while(next!=routerid-1){
                    prev = next;
                    next = topo[next];
                }
                fprintf(fp, "R%d -> R%d -> R%d, %d\n",routerid,i+1,prev+1,dist[i]);
            }
        }
    }
    fclose(fp);
}

void logmessage(msg_type type, int buffer[],char* filename,int routerid){
    FILE *fp = fopen(filename,"a");
    pkt_HELLO *hello;
    pkt_LSPDU *lspdu;
    switch(type){
        case INIT_s:
            fprintf(fp,"##R%d -> pkt_INIT sent##\n",routerid);
            break;
        case CIRCUIT_DB_r:
            fprintf(fp,"**R%d -> circuit_DB received**\n",routerid);
            break;
        case HELLO_s:
            hello = (pkt_HELLO*)buffer;
            fprintf(fp,"##R%d -> pkt_HELLO sent to link%d ##\n",routerid,hello->link_id);
            break;
        case HELLO_r:
            hello = (pkt_HELLO*)buffer;
            fprintf(fp,"**R%d -> pkt_HELLO received from link%d **\n",routerid,hello->link_id);
            break;
        case LSPDU_s:
            lspdu = (pkt_LSPDU*)buffer;
            fprintf(fp,"##R%d -> pkt_LSPDU sent to link%d, containing link%d, cost%d ##\n",routerid,lspdu->via,lspdu->link_id,lspdu->cost);
            break;
        case LSPDU_r:
            lspdu = (pkt_LSPDU*)buffer;
            fprintf(fp,"**R%d -> pkt_LSPDU received, router%d, link%d, cost%d **\n",routerid,lspdu->router_id,lspdu->link_id,lspdu->cost);
            break;
        case LSPDU_repeat:
            fprintf(fp,"--R%d -> repeated pkt_LSPDU, discarded --\n",routerid);
            break;
        default:
            break;
    }
    fclose(fp);
}

int updateTopology(circuit_DB routers[],pkt_LSPDU pkt)
{
    //return 0 if the link is already in database, return 1 if it is a new link
    int i;
    for(i=0;i<routers[pkt.router_id-1].nbr_link;i++)
    {
        if(routers[pkt.router_id-1].linkcost[i].link == pkt.link_id){
            if(routers[pkt.router_id-1].linkcost[i].cost == pkt.cost){
                //if cost is same, this LSPDU is a repeated one, discard the LSPDU and return 0
                return 0;
            }
            else{
                //update the cost of the link and run dijkstra again
                routers[pkt.router_id-1].linkcost[i].cost = pkt.cost;
                return 1;
            }
        }
    }

    int linkIndex = routers[pkt.router_id-1].nbr_link;
    routers[pkt.router_id-1].nbr_link ++;
    routers[pkt.router_id-1].linkcost[linkIndex].link = pkt.link_id;
    routers[pkt.router_id-1].linkcost[linkIndex].cost = pkt.cost;
    return 1;
}

void constructLSPDU(int *counter, pkt_LSPDU packets[], circuit_DB self,int routerid)
{
    int i;
    *counter = 0;
    /*nbr_link is smaller than or equal to NBR_ROUTER, hence this works*/
    for(i=0;i<self.nbr_link;i++){
        *counter = *counter+1;
        packets[i].sender=routerid;
        packets[i].router_id=routerid;
        packets[i].link_id = self.linkcost[i].link;
        packets[i].cost = self.linkcost[i].cost;
        packets[i].via = -1; // to be initialised when sending
    }
}

void sendLSPDU(int sock, struct sockaddr_in nse_addr, pkt_LSPDU packets[],int num_packets, int link,char* filename)
{
    //send LSPDU containing links of the router to one destination router
    int i;
    for(i=0;i<num_packets;i++){
        packets[i].via = link;
        if(sendto(sock,&packets[i],sizeof packets[i],0,(struct sockaddr*) &nse_addr, sizeof nse_addr)<0)
            error("sendto() failed at sending LSPDU");
        logmessage(LSPDU_s,(int*)&packets[i],filename,packets[i].sender);
    }
}

void forwardLSPDU(int sock, struct sockaddr_in nse_addr, pkt_LSPDU packet, int hellolinks[], int numhello, int routerid,char* filename)
{
    //change sender field and forward the LSPDU to all neighbours ( change via field )
    int rcvfrom = packet.via;
    packet.sender = routerid;
    int i;
    for(i=0;i<numhello;i++){
        if(hellolinks[i]!=rcvfrom){
            packet.via = hellolinks[i];
            if(sendto(sock,&packet,sizeof packet,0,(struct sockaddr*) &nse_addr, sizeof nse_addr)<0)
                error("sendto() failed at forwarding LSPDU");
            logmessage(LSPDU_s,(int*)&packet,filename,routerid);
        }
    }
}

void shortestPath(circuit_DB routers[], int topo[], int dist[], int self)
{
    //run dijkstra's algorithm to determine shortest path and topology
    //topo and dist 0-4 corresponds to router 1-5
    //self is router id, index start from 1

    //initialist topology and shortest distance
    int i,j,k,m;
    for(i=0;i<NBR_ROUTER;i++){
        topo[i]=-1;
        dist[i]=INT_MAX;
    }

    dist[self-1]=0;  /*set distance to self to 0*/
    int done[NBR_ROUTER] = {0}; /*keep track of nodes done with*/
    for(i=0;i<NBR_ROUTER;i++){
        int shortestDist = INT_MAX;
        int curr = -1; /*current node the algo is processing*/
        //locate node with least distance
        for(j=0;j<NBR_ROUTER;j++){
            if(dist[j]<shortestDist && !done[j]){
                curr = j;
                shortestDist = dist[j];
            }
        }
        //terminate loop cannot find a node with dist less than INT_MAX
        if(curr == -1)
            break;

        done[curr]=1;

        //for each edge of the node, find the destination and update distance
        for(j=0;j<routers[curr].nbr_link;j++){
            int link_num = routers[curr].linkcost[j].link;
            int link_cost = routers[curr].linkcost[j].cost;
            int destNode = -1;
            //figure out the link's other end
            for(k=0;k<NBR_ROUTER;k++){
                if(k==curr)
                    continue;
                if (destNode != -1)
                    break;
                //find an edge with same edge number and mark that node as destination
                for(m=0;m<routers[k].nbr_link;m++){
                    if(routers[k].linkcost[m].link == link_num){
                        destNode = k;
                        break;
                    }
                }
            }

            //if can find the edge's destination
            if(destNode != -1){
                if(link_cost + dist[curr] < dist[destNode]){
                    dist[destNode] = link_cost + dist[curr];
                    topo[destNode] = curr;
                }
            }
        }
    }
}
